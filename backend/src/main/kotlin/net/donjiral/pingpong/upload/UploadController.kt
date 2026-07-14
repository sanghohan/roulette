package net.donjiral.pingpong.upload

import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.net.URLEncoder
import java.util.UUID

@RestController
@RequestMapping("/api/uploads")
class UploadController(
    private val props: S3Properties,
    private val s3Provider: ObjectProvider<S3Client>
) {
    // 인라인 표시되는 미디어 (기존 동작 유지)
    private val allowedMedia = setOf(
        "image/jpeg", "image/png", "image/gif", "image/webp",
        "video/mp4", "video/webm"
    )

    // 첨부파일로 허용하는 확장자 (다운로드 방식으로 제공)
    private val allowedAttachExt = setOf(
        "pdf", "html", "htm", "txt", "md", "csv", "zip",
        "doc", "docx", "xls", "xlsx", "ppt", "pptx", "hwp", "hwpx"
    )

    private val attachContentTypes = mapOf(
        "pdf" to "application/pdf", "html" to "text/html", "htm" to "text/html",
        "txt" to "text/plain", "md" to "text/markdown", "csv" to "text/csv",
        "zip" to "application/zip",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "hwp" to "application/x-hwp", "hwpx" to "application/x-hwpx"
    )

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun upload(@RequestParam("file") file: MultipartFile): Map<String, String> {
        if (!props.enabled) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "파일 업로드가 비활성화되어 있습니다")
        }
        val s3 = s3Provider.ifAvailable
            ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "S3 클라이언트가 준비되지 않았습니다")

        if (file.isEmpty) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "빈 파일입니다")

        val contentType = file.contentType ?: ""
        val originalName = (file.originalFilename ?: "file").substringAfterLast('/').substringAfterLast('\\')
        val ext = originalName.substringAfterLast('.', "").lowercase()

        return when {
            contentType in allowedMedia -> uploadMedia(s3, file, contentType)
            ext in allowedAttachExt -> uploadAttachment(s3, file, originalName, ext)
            else -> throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "허용되지 않는 형식입니다 (이미지·영상 또는 ${allowedAttachExt.joinToString(", ")})"
            )
        }
    }

    /** 이미지/영상 — 기존 방식 그대로 (인라인 표시) */
    private fun uploadMedia(s3: S3Client, file: MultipartFile, contentType: String): Map<String, String> {
        val ext = when (contentType) {
            "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/gif" -> "gif"
            "image/webp" -> "webp"; "video/mp4" -> "mp4"; "video/webm" -> "webm"
            else -> "bin"
        }
        val key = "${props.keyPrefix}${UUID.randomUUID()}.$ext"
        val req = PutObjectRequest.builder()
            .bucket(props.bucket)
            .key(key)
            .contentType(contentType)
            .build()
        s3.putObject(req, RequestBody.fromInputStream(file.inputStream, file.size))
        return mapOf("url" to publicUrl(key), "name" to key.substringAfterLast('/'))
    }

    /** 문서 첨부파일 — 파일명 보존 + 다운로드 방식(Content-Disposition: attachment) */
    private fun uploadAttachment(
        s3: S3Client, file: MultipartFile, originalName: String, ext: String
    ): Map<String, String> {
        // 파일명 정리: 경로·제어문자 제거, 공백 → _, 너무 길면 자르기
        val base = originalName.substringBeforeLast('.')
            .replace(Regex("[\\p{Cntrl}/\\\\:*?\"<>|#%&{}$!@+`=]"), "")
            .replace(Regex("\\s+"), "_")
            .take(80)
            .ifBlank { "file" }
        val safeName = "$base.$ext"
        val key = "${props.keyPrefix}files/${UUID.randomUUID()}/$safeName"

        val encoded = URLEncoder.encode(safeName, Charsets.UTF_8).replace("+", "%20")
        val req = PutObjectRequest.builder()
            .bucket(props.bucket)
            .key(key)
            .contentType(attachContentTypes[ext] ?: "application/octet-stream")
            // html 등이 브라우저에서 실행되지 않도록 항상 다운로드로 제공
            .contentDisposition("attachment; filename*=UTF-8''$encoded")
            .build()
        s3.putObject(req, RequestBody.fromInputStream(file.inputStream, file.size))
        return mapOf("url" to publicUrl(key), "name" to safeName)
    }

    private fun publicUrl(key: String): String {
        val encodedKey = key.split('/').joinToString("/") {
            URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20")
        }
        return if (props.publicBaseUrl.isNotBlank())
            "${props.publicBaseUrl.trimEnd('/')}/$encodedKey"
        else
            "https://${props.bucket}.s3.${props.region}.amazonaws.com/$encodedKey"
    }
}
