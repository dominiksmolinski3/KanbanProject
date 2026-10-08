variable "resource_group_name" {
  type = string
}

variable "resource_group_id" {
  type = string
}

variable "location" {
  type = string
}

variable "env" {
  type = string
}

variable "subnet_id" {
  type = string
}

variable "subnet_cidr" {
  description = "The monitoring subnet. The VM takes its fifth address, the first one Azure does not reserve, so the push URL is known before the VM exists."
  type        = string
}

variable "size" {
  type    = string
  default = "Standard_B2ls_v2"
}

variable "data_disk_size_gb" {
  type    = number
  default = 32
}

variable "key_vault_id" {
  type = string
}

variable "credentials_version" {
  description = "Bump to rotate the push and read credentials."
  type        = number
  default     = 1
}

variable "operator_object_ids" {
  description = "Entra object ids allowed to run commands on the VM, which is how it is configured. Owners of the resource group already can."
  type        = list(string)
  default     = []
}

variable "alerts_enabled" {
  type = bool
}

variable "action_group_id" {
  type = string
}

variable "tags" {
  type = map(string)
}
