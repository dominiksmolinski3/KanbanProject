package pl.myproject.kanbanproject2.messaging;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.myproject.kanbanproject2.user.User;

import java.util.List;

@RestController
@RequestMapping("/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversations;
    private final MessageDeliveryService delivery;

    @GetMapping
    public ResponseEntity<List<ConversationDto>> list(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(conversations.list(currentUser));
    }

    @PostMapping
    public ResponseEntity<ConversationDto> create(@Valid @RequestBody CreateConversationRequest request,
                                                  @AuthenticationPrincipal User currentUser) {
        ConversationService.Created created = conversations.create(currentUser, request);
        return ResponseEntity.status(created.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(created.conversation());
    }

    @GetMapping("/contacts")
    public ResponseEntity<List<ContactDto>> contacts(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(conversations.contacts(currentUser));
    }

    @GetMapping("/lookup")
    public ResponseEntity<ContactDto> lookup(@RequestParam String email, @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(conversations.lookup(currentUser, email));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ConversationDto> get(@PathVariable Long id, @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(conversations.get(currentUser, id));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ConversationDto> update(@PathVariable Long id,
                                                  @Valid @RequestBody UpdateConversationRequest request,
                                                  @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(conversations.update(currentUser, id, request));
    }

    @PostMapping("/{id}/members")
    public ResponseEntity<ConversationDto> addMembers(@PathVariable Long id,
                                                      @Valid @RequestBody AddMembersRequest request,
                                                      @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(conversations.addMembers(currentUser, id, request));
    }

    @PutMapping("/{id}/members/{userId}")
    public ResponseEntity<ConversationDto> setRole(@PathVariable Long id, @PathVariable Integer userId,
                                                   @Valid @RequestBody MemberRoleRequest request,
                                                   @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(conversations.setRole(currentUser, id, userId, request));
    }

    @DeleteMapping("/{id}/members/{userId}")
    public ResponseEntity<Void> removeMember(@PathVariable Long id, @PathVariable Integer userId,
                                             @AuthenticationPrincipal User currentUser) {
        conversations.removeMember(currentUser, id, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/accept")
    public ResponseEntity<ConversationDto> accept(@PathVariable Long id, @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(conversations.accept(currentUser, id));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable Long id, @Valid @RequestBody ReadRequest request,
                                         @AuthenticationPrincipal User currentUser) {
        conversations.markRead(currentUser, id, request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/typing")
    public ResponseEntity<Void> typing(@PathVariable Long id, @AuthenticationPrincipal User currentUser) {
        conversations.typing(currentUser, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/devices")
    public ResponseEntity<List<DirectoryEntryDto>> directory(@PathVariable Long id,
                                                             @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(delivery.directory(currentUser, id));
    }

    @PostMapping("/{id}/key-packages")
    public ResponseEntity<List<ClaimedKeyPackageDto>> claim(@PathVariable Long id,
                                                            @Valid @RequestBody ClaimRequest request,
                                                            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(delivery.claim(currentUser, id, request));
    }

    @PostMapping("/{id}/commits")
    public ResponseEntity<PostedDto> commit(@PathVariable Long id, @Valid @RequestBody CommitRequest request,
                                            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED).body(delivery.commit(currentUser, id, request));
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<PostedDto> post(@PathVariable Long id, @Valid @RequestBody MessageRequest request,
                                          @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED).body(delivery.post(currentUser, id, request));
    }

    @GetMapping("/{id}/messages")
    public ResponseEntity<List<MlsMessageDto>> messages(@PathVariable Long id,
                                                        @RequestParam(required = false) Long after,
                                                        @RequestParam(required = false) Integer size,
                                                        @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(delivery.messages(currentUser, id, after, size));
    }
}
