package net.donjiral.pingpong.upload

import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.util.UUID

@RestController
@RequestMapping("/api/uploads")
class UploadController(
    private val props: S3Properties,
    private val s3Provider: ObjectProvider<S3Client>
) {
    private val allowed = setOf(
        "image/jpeg", "image/png", "image/gif", "image/webp",
        "video/mp4", "video/webm"
    )

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun upload(@RequestParam("file") file: MultipartFile): Map<String, String> {
        if (!props.enabled) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "이미지 업로드가 비활성화되어 있습니다")
        }
        val s3 = s3Provider.ifAvailable
            ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "S3 클라이언트가 준비되지 않았습니다")

        if (file.isEmpty) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "빈 파일입니다")
        val contentType = file.contentType ?: ""
        if (contentType !in allowed) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "허용되지 않는 형식입니다 ($contentType)")
        }

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

        val url = if (props.publicBaseUrl.isNotBlank())
            "${props.publicBaseUrl.trimEnd('/')}/$key"
        else
            "https://${props.bucket}.s3.${props.region}.amazonaws.com/$key"

        return mapOf("url" to url)
    }
}
