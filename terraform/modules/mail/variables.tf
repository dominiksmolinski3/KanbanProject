variable "resource_group_name" {
  type        = string
  description = "An existing resource group. Communication Services resources are global, so the group's own region does not matter."
}

variable "communication_service_name" {
  type = string
}

variable "email_service_name" {
  type = string
}

variable "data_location" {
  type    = string
  default = "Europe"
}

variable "tags" {
  type    = map(string)
  default = {}
}
