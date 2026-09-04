# Front Door with a private origin

**Status: not built.** This is the way out if attachment traffic ever outgrows the container, written
down while the reasoning is fresh so that the next person facing a slow download does not reach for
the obvious wrong fix — reopening the storage account.

## The problem this would solve

Attachments are stored in Blob Storage and the account is closed to the internet
(`public_network_access_enabled = false`, a `network_rules` default of `Deny`, and a private
endpoint in `snet-storage`). Nothing but the application can reach it. That means a download is
`GET /api/tasks/{id}/attachments/{aid}/content`, streamed from storage through the app to the
browser.

The bytes therefore cost the container's CPU and bandwidth, on `max_replicas = 1`. Streaming keeps
memory flat — nothing on that path holds a whole file — but one process carries all of it.

**The signal to watch** is attachment traffic contending with ordinary request serving: board loads
getting slower while somebody downloads, or the container's CPU sitting high with a modest request
rate. Not "we have a lot of attachments" — a lot of *stored* attachments cost nothing here, because
listing them is a single Postgres query and touches Azure not at all.

## Cheaper things to do first

In this order. Do not skip to Front Door because it is the interesting option.

1. **Raise the container.** It is `cpu = 0.25` / `memory = "0.5Gi"` in
   [modules/container_app/main.tf](modules/container_app/main.tf), which is the smallest thing
   Container Apps offers. Proxying bytes is cheap per byte; this is the direct fix and it is one
   line.
2. **Raise the file limit deliberately, or lower it.** `MAX_ATTACHMENT_SIZE` is 10 MB and
   `spring.servlet.multipart.max-file-size` matches. Most of the pressure comes from a few large
   files rather than many small ones.
3. **More replicas** — but read `max_replicas` in
   [modules/container_app/variables.tf](modules/container_app/variables.tf) first. It is pinned at 1
   because the STOMP broker and the auth rate limiter both hold state in the JVM. Moving those out
   is a bigger job than this document describes, and it is worth doing for its own reasons.

Front Door is worth it when the bytes need to stop transiting the application at all.

## The design

Front Door **Premium** sits in front of the storage account and reaches it over a Private Link
origin. The browser talks to Front Door's edge; Front Door talks to storage privately; the storage
account stays closed to the internet exactly as it is now.

```
browser ──HTTPS──> Front Door edge ──Private Link──> storage account (still closed)
                        ▲                                    ▲
                        │                                    │ snet-storage private endpoint
                   public surface                            │
                                              Container App ─┘  (unchanged)
```

The two private connections are separate. Ours lives in `snet-storage` and is what the application
uses; Front Door's is created by Front Door in Microsoft's network and does not touch our VNet.
Both work against a closed account for the same reason: **private endpoint traffic bypasses the
storage firewall entirely.**

### Authorization comes back as a SAS

Front Door does not authenticate anybody. Something has to decide whether the person asking may
read that blob, and the only mechanism that survives the hop is a **user delegation SAS** in the
query string: the browser presents it to Front Door, Front Door forwards it to storage, and storage
validates it.

Which means **this change restores the machinery that closing the account deleted.** Concretely,
what comes back:

- `BlobStore.readUrl(...)` and a `UserDelegationKeySource` that caches the delegation key (fetching
  one is a round trip; signing is otherwise arithmetic).
- Signed response headers — `rscd` and `rsct` — so a file still downloads under the name its
  uploader gave it and as the type it was uploaded as, neither changeable by editing the URL.
- A `GET .../{id}/link` route answering a short-lived URL, and a client that navigates to it
  instead of fetching bytes.
- The account needs no key for any of this: a user delegation SAS is signed with a key the service
  issues to the app's managed identity, so `shared_access_key_enabled = false` stays.

`git log` for the commit that closed the account has all of it; it was deleted rather than left
unused precisely so that restoring it would be a deliberate act.

What is **not** restored is the public storage endpoint. That is the whole point: the SAS is
presented to Front Door, and the account remains unreachable except through a private link.

### Terraform

Sketched below and validated against `azurerm ~> 4.46` — resource and argument names are real, the
values are illustrative.

```hcl
resource "azurerm_cdn_frontdoor_profile" "main" {
  name                = "afd-kanban-${var.env}"
  resource_group_name = var.resource_group_name
  sku_name            = "Premium_AzureFrontDoor" # Private Link origins need Premium
}

resource "azurerm_cdn_frontdoor_endpoint" "attachments" {
  name                     = "attachments"
  cdn_frontdoor_profile_id = azurerm_cdn_frontdoor_profile.main.id
}

resource "azurerm_cdn_frontdoor_origin_group" "blob" {
  name                     = "blob"
  cdn_frontdoor_profile_id = azurerm_cdn_frontdoor_profile.main.id
  load_balancing {}
}

resource "azurerm_cdn_frontdoor_origin" "blob" {
  name                          = "blob"
  cdn_frontdoor_origin_group_id = azurerm_cdn_frontdoor_origin_group.blob.id
  enabled                       = true

  host_name                      = var.storage_blob_host # <account>.blob.core.windows.net
  origin_host_header             = var.storage_blob_host # see the trap below - not the Front Door host
  https_port                     = 443
  http_port                      = 80
  priority                       = 1
  weight                         = 1
  certificate_name_check_enabled = true

  private_link {
    request_message        = "Front Door origin for task attachments"
    target_type            = "blob"
    location               = var.location
    private_link_target_id = var.storage_account_id
  }
}

resource "azurerm_cdn_frontdoor_route" "attachments" {
  name                          = "attachments"
  cdn_frontdoor_endpoint_id     = azurerm_cdn_frontdoor_endpoint.attachments.id
  cdn_frontdoor_origin_group_id = azurerm_cdn_frontdoor_origin_group.blob.id
  cdn_frontdoor_origin_ids      = [azurerm_cdn_frontdoor_origin.blob.id]

  supported_protocols    = ["Https"]
  patterns_to_match      = ["/*"]
  forwarding_protocol    = "HttpsOnly"
  https_redirect_enabled = true
  link_to_default_domain = true
  # No `cache` block. See the caching trap below before adding one.
}
```

A WAF policy (`azurerm_cdn_frontdoor_firewall_policy` plus
`azurerm_cdn_frontdoor_security_policy`) belongs here too — Front Door is a new public surface, and
Premium includes the WAF you are already paying for.

## Four traps

**1. Premium is the price of admission, and it is not incremental.** Private Link origins are a
Premium-only feature; Standard cannot do this at all. Premium carries a base monthly charge roughly
an order of magnitude above Standard's, independent of traffic. Check current pricing before
committing — for an environment of this size it is likely to be the largest single line on the bill,
and that alone may decide the question in favour of a bigger container.

**2. The private endpoint connection needs approving, out of band.** Front Door creates a pending
connection request against the storage account; nothing is served until somebody approves it. It
cannot be approved in the same `terraform apply` that creates it, because the connection does not
exist until Front Door has made it. Expect either a manual
`az network private-endpoint-connection approve` or a second apply, and expect the first apply to
look finished while the route still 403s.

**3. `origin_host_header` must be the storage FQDN, not the Front Door one.** A SAS signature covers
a canonicalized resource derived from the account name — `/blob/<account>/<container>/<blob>`. The
application signs against the storage account's own hostname, so storage has to receive the request
believing that is the host it was asked for. Set `origin_host_header` to the Front Door hostname and
every download fails signature validation, with an error that says nothing about host headers.

**4. Caching and SAS are a security interaction, not a performance knob.** The instinct with a CDN
is to turn caching on. A cached response keyed without the query string means the *first* person's
authorized fetch is replayed to everyone who asks for that path afterwards — including after their
SAS has expired. If you cache at all, `query_string_caching_behavior = "UseQueryString"` is the
minimum, and even then the cache outlives the token that filled it. The safe default is no `cache`
block.

Trap 4 has a consequence worth stating plainly: **with caching off, Front Door is a private-origin
proxy and nothing else.** The win is real — bytes stop transiting your container, and you get edge
termination and a WAF — but it is not the "free CDN" the shape of the thing suggests. Weigh it
against option 1 above accordingly.

## What this does not change

- **The database/blob split.** An attachment is still half a row in Postgres and half a blob, joined
  by a string with no foreign key. The write ordering in `TaskAttachmentService` and the retention
  tie in [modules/storage/variables.tf](modules/storage/variables.tf) still carry that.
- **Upload.** Uploads keep going through the application, which is where the size and type checks
  live and where the caller is identified. Only reads move.
- **The app's own private endpoint.** `snet-storage` stays exactly as it is; the app still reads and
  writes blobs over it, and still creates the container on first start.
- **Local development.** Azurite in `docker-compose.yml` is unaffected — there is no Front Door
  locally, and the client would need to handle both shapes or the compose stack would need the
  streaming route kept alongside.

## Checkov

Two skips were earned back when the account was closed: `CKV_AZURE_59` (public network access) and
`CKV2_AZURE_33` (private endpoint). **Neither should come back.** If a change here makes either fire
again, the account has been reopened somewhere — which is the exact outcome this document exists to
prevent.
