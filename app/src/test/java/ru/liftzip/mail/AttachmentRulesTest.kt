package ru.liftzip.mail

import org.junit.Assert.*
import org.junit.Test

class AttachmentRulesTest {
    @Test fun attachmentNamesCannotEscapeThePrivateCacheDirectory() {
        assertEquals("secret.pdf",AttachmentRules.safeName("../../secret.pdf"))
        assertEquals("photo.jpg",AttachmentRules.safeName("C:\\private\\photo.jpg"))
        assertEquals("Вложение",AttachmentRules.safeName(".."))
    }
    @Test fun controlCharactersAreRemovedAndNamesAreBounded() {
        assertFalse(AttachmentRules.safeName("evil\u0000name\n.pdf").any{it.isISOControl()})
        assertTrue(AttachmentRules.safeName("a".repeat(500)).length<=160)
    }
    @Test fun genericServerMimeIsResolvedFromTheFilename() {
        assertEquals("application/pdf",AttachmentRules.mime("Счёт.PDF","application/octet-stream"))
        assertEquals("image/jpeg",AttachmentRules.mime("photo.jpeg",""))
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",AttachmentRules.mime("price.xlsx","application/octet-stream"))
    }
    @Test fun explicitServerMimeIsPreservedAndParametersRemoved() {
        assertEquals("text/plain",AttachmentRules.mime("message.bin","text/plain; charset=UTF-8"))
    }
    @Test fun OnlyImagesAndPdfUseTheInternalPreview() {
        assertTrue(AttachmentRules.canPreview("application/pdf"))
        assertTrue(AttachmentRules.canPreview("image/png"))
        assertFalse(AttachmentRules.canPreview("image/svg+xml"))
        assertFalse(AttachmentRules.canPreview("text/html"))
    }
}
