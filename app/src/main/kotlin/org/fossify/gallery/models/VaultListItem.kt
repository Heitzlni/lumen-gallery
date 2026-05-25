package org.fossify.gallery.models

sealed class VaultListItem {
    data class Header(val title: String) : VaultListItem()
    data class Entry(val item: VaultItem) : VaultListItem()
}
