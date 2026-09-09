package com.anchor.data.export

/** How the daily Markdown file is shaped. */
enum class NoteFormat {
    /** Exactly the format in AGENTS.md §5. */
    PLAIN,

    /** PLAIN plus YAML frontmatter and a previous-day wikilink. */
    OBSIDIAN,
}
