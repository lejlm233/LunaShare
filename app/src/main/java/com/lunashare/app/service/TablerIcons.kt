package com.lunashare.app.service

/**
 * Inline Tabler Icons (https://github.com/tabler/tabler-icons) SVG strings.
 * Used by [WebUi] to render the offline WebDAV file manager without any
 * external dependency or emoji. Every icon uses stroke="currentColor" so it
 * automatically follows the page's light / dark theme.
 */
object TablerIcons {

    private val ICON_SVG: Map<String, String> = mapOf(
        "moon" to "<svg class='ti' xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round' > <path d='M12 3c.132 0 .263 0 .393 0a7.5 7.5 0 0 0 7.92 12.446a9 9 0 1 1 -8.313 -12.454l0 .008' /> </svg>",
        "sun" to "<svg class='ti' xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round' > <path d='M8 12a4 4 0 1 0 8 0a4 4 0 1 0 -8 0' /> <path d='M3 12h1m8 -9v1m8 8h1m-9 8v1m-6.4 -15.4l.7 .7m12.1 -.7l-.7 .7m0 11.4l.7 .7m-12.1 -.7l-.7 .7' /> </svg>",
        "refresh" to "<svg class='ti' xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round' > <path d='M20 11a8.1 8.1 0 0 0 -15.5 -2m-.5 -4v4h4' /> <path d='M4 13a8.1 8.1 0 0 0 15.5 2m.5 4v-4h-4' /> </svg>",
        "search" to "<svg class='ti' xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round' > <path d='M3 10a7 7 0 1 0 14 0a7 7 0 1 0 -14 0' /> <path d='M21 21l-6 -6' /> </svg>",
        "upload" to "<svg class='ti' xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round' > <path d='M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2 -2v-2' /> <path d='M7 9l5 -5l5 5' /> <path d='M12 4l0 12' /> </svg>",
        "folder-plus" to "<svg class='ti' xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round' > <path d='M12 19h-7a2 2 0 0 1 -2 -2v-11a2 2 0 0 1 2 -2h4l3 3h7a2 2 0 0 1 2 2v3.5' /> <path d='M16 19h6' /> <path d='M19 16v6' /> </svg>",
        "check" to "<svg class='ti' xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round' > <path d='M5 12l5 5l10 -10' /> </svg>",
        "download" to "<svg class='ti' xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round' > <path d='M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2 -2v-2' /> <path d='M7 11l5 5l5 -5' /> <path d='M12 4l0 12' /> </svg>",
        "trash" to "<svg class='ti' xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round' > <path d='M4 7l16 0' /> <path d='M10 11l0 6' /> <path d='M14 11l0 6' /> <path d='M5 7l1 12a2 2 0 0 0 2 2h8a2 2 0 0 0 2 -2l1 -12' /> <path d='M9 7v-3a1 1 0 0 1 1 -1h4a1 1 0 0 1 1 1v3' /> </svg>",
        "x" to "<svg class='ti' xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round' > <path d='M18 6l-12 12' /> <path d='M6 6l12 12' /> </svg>",
        "cloud-upload" to "<svg class='ti' xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round' > <path d='M7 18a4.6 4.4 0 0 1 0 -9a5 4.5 0 0 1 11 2h1a3.5 3.5 0 0 1 0 7h-1' /> <path d='M9 15l3 -3l3 3' /> <path d='M12 12l0 9' /> </svg>",
        "home" to "<svg class='ti' xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round' > <path d='M5 12l-2 0l9 -9l9 9l-2 0' /> <path d='M5 12v7a2 2 0 0 0 2 2h10a2 2 0 0 0 2 -2v-7' /> <path d='M9 21v-6a2 2 0 0 1 2 -2h2a2 2 0 0 1 2 2v6' /> </svg>",
        "folder-open" to "<svg class='ti' xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round' > <path d='M5 19l2.757 -7.351a1 1 0 0 1 .936 -.649h12.307a1 1 0 0 1 .986 1.164l-.996 5.211a2 2 0 0 1 -1.964 1.625h-14.026a2 2 0 0 1 -2 -2v-11a2 2 0 0 1 2 -2h4l3 3h7a2 2 0 0 1 2 2v2' /> </svg>",
        "folder" to "<svg class='ti' xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round' > <path d='M5 4h4l3 3h7a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-11a2 2 0 0 1 2 -2' /> </svg>",
        "photo" to "<svg class='ti' xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round' > <path d='M15 8h.01' /> <path d='M3 6a3 3 0 0 1 3 -3h12a3 3 0 0 1 3 3v12a3 3 0 0 1 -3 3h-12a3 3 0 0 1 -3 -3v-12' /> <path d='M3 16l5 -5c.928 -.893 2.072 -.893 3 0l5 5' /> <path d='M14 14l1 -1c.928 -.893 2.072 -.893 3 0l3 3' /> </svg>",
        "movie" to "<svg class='ti' xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round' > <path d='M4 6a2 2 0 0 1 2 -2h12a2 2 0 0 1 2 2v12a2 2 0 0 1 -2 2h-12a2 2 0 0 1 -2 -2l0 -12' /> <path d='M8 4l0 16' /> <path d='M16 4l0 16' /> <path d='M4 8l4 0' /> <path d='M4 16l4 0' /> <path d='M4 12l16 0' /> <path d='M16 8l4 0' /> <path d='M16 16l4 0' /> </svg>",
        "music" to "<svg class='ti' xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round' > <path d='M3 17a3 3 0 1 0 6 0a3 3 0 0 0 -6 0' /> <path d='M13 17a3 3 0 1 0 6 0a3 3 0 0 0 -6 0' /> <path d='M9 17v-13h10v13' /> <path d='M9 8h10' /> </svg>",
        "file-zip" to "<svg class='ti' xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round' > <path d='M6 20.735a2 2 0 0 1 -1 -1.735v-14a2 2 0 0 1 2 -2h7l5 5v11a2 2 0 0 1 -2 2h-1' /> <path d='M11 17a2 2 0 0 1 2 2v2a1 1 0 0 1 -1 1h-2a1 1 0 0 1 -1 -1v-2a2 2 0 0 1 2 -2' /> <path d='M11 5l-1 0' /> <path d='M13 7l-1 0' /> <path d='M11 9l-1 0' /> <path d='M13 11l-1 0' /> <path d='M11 13l-1 0' /> <path d='M13 15l-1 0' /> </svg>",
        "file-type-pdf" to "<svg class='ti' xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round' > <path d='M14 3v4a1 1 0 0 0 1 1h4' /> <path d='M5 12v-7a2 2 0 0 1 2 -2h7l5 5v4' /> <path d='M5 18h1.5a1.5 1.5 0 0 0 0 -3h-1.5v6' /> <path d='M17 18h2' /> <path d='M20 15h-3v6' /> <path d='M11 15v6h1a2 2 0 0 0 2 -2v-2a2 2 0 0 0 -2 -2h-1' /> </svg>",
        "file-text" to "<svg class='ti' xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round' > <path d='M14 3v4a1 1 0 0 0 1 1h4' /> <path d='M17 21h-10a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2h7l5 5v11a2 2 0 0 1 -2 2' /> <path d='M9 9l1 0' /> <path d='M9 13l6 0' /> <path d='M9 17l6 0' /> </svg>",
        "file-spreadsheet" to "<svg class='ti' xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round' > <path d='M14 3v4a1 1 0 0 0 1 1h4' /> <path d='M17 21h-10a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2h7l5 5v11a2 2 0 0 1 -2 2' /> <path d='M8 11h8v7h-8l0 -7' /> <path d='M8 15h8' /> <path d='M11 11v7' /> </svg>",
        "presentation" to "<svg class='ti' xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round' > <path d='M3 4l18 0' /> <path d='M4 4v10a2 2 0 0 0 2 2h12a2 2 0 0 0 2 -2v-10' /> <path d='M12 16l0 4' /> <path d='M9 20l6 0' /> <path d='M8 12l3 -3l2 2l3 -3' /> </svg>",
        "file" to "<svg class='ti' xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round' > <path d='M14 3v4a1 1 0 0 0 1 1h4' /> <path d='M17 21h-10a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2h7l5 5v11a2 2 0 0 1 -2 2' /> </svg>",
        "binary" to "<svg class='ti' xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round' > <path d='M11 10v-5h-1m8 14v-5h-1' /> <path d='M15 5.5a.5 .5 0 0 1 .5 -.5h2a.5 .5 0 0 1 .5 .5v4a.5 .5 0 0 1 -.5 .5h-2a.5 .5 0 0 1 -.5 -.5l0 -4' /> <path d='M10 14.5a.5 .5 0 0 1 .5 -.5h2a.5 .5 0 0 1 .5 .5v4a.5 .5 0 0 1 -.5 .5h-2a.5 .5 0 0 1 -.5 -.5l0 -4' /> <path d='M6 10h.01m-.01 9h.01' /> </svg>",
        "pencil" to "<svg class='ti' xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round' > <path d='M4 20h4l10.5 -10.5a2.828 2.828 0 1 0 -4 -4l-10.5 10.5v4' /> <path d='M13.5 6.5l4 4' /> </svg>",
        "archive" to "<svg class='ti' xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round' > <path d='M3 6a2 2 0 0 1 2 -2h14a2 2 0 0 1 2 2a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2' /> <path d='M5 8v10a2 2 0 0 0 2 2h10a2 2 0 0 0 2 -2v-10' /> <path d='M10 12l4 0' /> </svg>",
        "chevron-up" to "<svg class='ti' xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round' > <path d='M6 15l6 -6l6 6' /> </svg>",
        "chevron-down" to "<svg class='ti' xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='2' stroke-linecap='round' stroke-linejoin='round' > <path d='M6 9l6 6l6 -6' /> </svg>"
    )

    /** Returns the JS object literal `{...}` that the template assigns to `var ICONS`. */
    fun iconsScript(): String {
        val b = StringBuilder("{")
        var first = true
        for ((k, v) in ICON_SVG) {
            if (!first) b.append(',')
            first = false
            b.append('"').append(k).append('"').append(':').append('"').append(v).append('"')
        }
        b.append("}")
        return b.toString()
    }

    /** Data-URI favicon (moon) for <link rel="icon">. */
    fun faviconDataUri(): String =
        "data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%230a84ff' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'><path d='M12 3c.132 0 .263 0 .393 0a7.5 7.5 0 0 0 7.92 12.446a9 9 0 1 1 -8.313 -12.454l0 .008'/></svg>"
}
