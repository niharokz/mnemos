package com.nihars.mnemos.core.vaultfs

import com.nihars.mnemos.core.text.NaturalCompare

enum class TreeSort { NAME, MODIFIED, SIZE }

/** Folders first, then by the chosen key; names compare naturally ("note 2" before "note 10"). */
fun List<VaultEntry>.sortedForTree(sort: TreeSort): List<VaultEntry> {
    val byName = Comparator<VaultEntry> { a, b -> NaturalCompare.compare(a.name, b.name) }
    val key: Comparator<VaultEntry> = when (sort) {
        TreeSort.NAME -> byName
        TreeSort.MODIFIED -> compareByDescending<VaultEntry> { it.modified }.then(byName)
        TreeSort.SIZE -> compareByDescending<VaultEntry> { it.size }.then(byName)
    }
    return sortedWith(compareBy<VaultEntry> { !it.isDirectory }.then(key))
}
