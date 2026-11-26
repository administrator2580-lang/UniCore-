package com.example

import com.example.data.model.DriveFolderLink
import com.example.data.repository.AiTutorRepository
import com.example.data.repository.GoogleDriveSyncRepository
import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun parseEmbeddedFolderHtml_extractsRealFileNamesInsideNestedFlipEntryDivs() {
    val repo = GoogleDriveSyncRepository()
    val folder = DriveFolderLink(
      id = "f1",
      title = "NCT 1",
      folderUrl = "https://drive.google.com/drive/folders/1ParentFolder123456",
      folderId = "1ParentFolder123456"
    )
    val sampleHtml = """
      <div class="flip-entries">
        <div class="flip-entry" id="entry-1AbCDefGhIJkLmNoPqRsT1" tabindex="0">
          <div class="flip-entry-info">
            <a href="https://drive.google.com/file/d/1AbCDefGhIJkLmNoPqRsT1/view?usp=drive_web" target="_blank">
              <div class="flip-entry-list-icon"></div>
              <div class="flip-entry-title">Renewable Energy Lec 1.pdf</div>
            </a>
          </div>
        </div>
        <div id="entry-2XyZAbCdEfGhIjKlMnOpQ2" class="flip-entry" tabindex="0">
          <div class="flip-entry-visual"></div>
          <div class="flip-entry-info">
            <a href="https://drive.google.com/file/d/2XyZAbCdEfGhIjKlMnOpQ2/view?usp=drive_web">
              <div class="flip-entry-title">&#1575;&#1604;&#1605;&#1581;&#1575;&#1590;&#1585;&#1577; 2 &amp; Notes.pptx</div>
            </a>
          </div>
        </div>
      </div>
    """.trimIndent()

    val files = repo.parseEmbeddedFolderHtml(sampleHtml, folder)
    assertEquals(2, files.size)
    assertEquals("Renewable Energy Lec 1.pdf", files[0].name)
    assertEquals("المحاضرة 2 & Notes.pptx", files[1].name)
  }

  @Test
  fun extractContentDisposition_decodesUtf8AndQuotedFilenames() {
    val repo = GoogleDriveSyncRepository()
    val utf8Header = "attachment;filename=\"fallback.pdf\";filename*=UTF-8''%D9%85%D8%AD%D8%A7%D8%B6%D8%B1%D8%A9%201.pdf"
    assertEquals("محاضرة 1.pdf", repo.extractFileNameFromContentDisposition(utf8Header))

    val quotedHeader = "attachment; filename=\"Solar_Cells_Sheet.pdf\""
    assertEquals("Solar_Cells_Sheet.pdf", repo.extractFileNameFromContentDisposition(quotedHeader))
  }

  @Test
  fun extractConfirmDownloadUrl_parsesDriveVirusScanForm() {
    val repo = GoogleDriveSyncRepository()
    val html = """
      <html><body>
        <form id="download-form" action="https://drive.usercontent.google.com/download" method="get">
          <input type="hidden" name="id" value="1AbCDefFileId">
          <input type="hidden" name="confirm" value="t">
          <input type="hidden" name="uuid" value="abc-123">
        </form>
      </body></html>
    """.trimIndent()
    val resolved = repo.extractConfirmDownloadUrl(html, "https://drive.google.com/uc?id=1AbCDefFileId")
    assertNotNull(resolved)
    assertTrue(resolved!!.contains("id=1AbCDefFileId"))
    assertTrue(resolved.contains("confirm=t"))
    assertTrue(resolved.contains("uuid=abc-123"))
  }

  @Test
  fun formatReadableTutorOutput_removesLatexAndMarkdownClutter() {
    val raw = """
      ### **السؤال الأول: إيجاد الجهد**
      * المقاومة (${'$'}R = 50\ \Omega${'$'}) والتيار (${'$'}${'$'}2\text{ A}${'$'}${'$'}).
      احسب ${'$'}V = I \times R${'$'} وكذلك \frac{120}{40} = 3.
    """.trimIndent()

    val cleaned = AiTutorRepository.formatReadableTutorOutput(raw)
    assertFalse(cleaned.contains("$"))
    assertFalse(cleaned.contains("###"))
    assertFalse(cleaned.contains("**"))
    assertFalse(cleaned.contains("\\Omega"))
    assertFalse(cleaned.contains("\\text"))
    assertTrue(cleaned.contains("Ω"))
    assertTrue(cleaned.contains("×"))
    assertTrue(cleaned.contains("(120 ÷ 40)"))
    assertTrue(cleaned.contains("• المقاومة"))
  }
}

