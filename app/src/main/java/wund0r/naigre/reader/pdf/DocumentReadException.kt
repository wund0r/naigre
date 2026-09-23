// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.pdf

import wund0r.naigre.reader.R

/** Expected input failures. Keep a stable diagnostic code; resolve wording at the UI boundary. */
enum class DocumentReadProblem(val messageRes: Int) {
    NOT_PDF(R.string.error_not_pdf),
    PASSWORD_PROTECTED(R.string.error_pdf_password),
    EMPTY_PDF(R.string.error_empty_pdf),
    CANNOT_OPEN_NOTE(R.string.error_open_note),
    NOTE_TOO_LARGE(R.string.error_note_too_large),
    EMPTY_ALBUM(R.string.error_empty_album),
    CANNOT_OPEN_IMAGE(R.string.error_open_image),
    INVALID_IMAGE(R.string.error_invalid_image),
}

class DocumentReadException(val problem: DocumentReadProblem) : IllegalArgumentException(problem.name)
