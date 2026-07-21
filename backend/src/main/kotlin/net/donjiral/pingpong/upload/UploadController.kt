package net.donjiral.pingpong.upload

import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.util.UUID

/**
 * 파일 업로드.
 *
 * S3 저장 비용이 무한정 늘어나지 않도록 여러 겹으로 막습니다.
 *  1) RateLimitFilter  - IP당 시간/일 업로드 횟수, 전체 일일 횟수
 *  2) UploadQuota      - 전체 일일 업로드 총 바이트
 *  3) 여기(컨트롤러)   - 관리자 인증, 형식별 용량 제한, 실제 파일 내용 검사
 *  4) S3 라이프사이클  - tmp/ 자동 삭제, 미완료 멀티파트 정리 (배포 가이드 참고)
 */
@RestController
@RequestMapping("/api/uploads")
class UploadController(
    private val props: S3Properties,
    private val quota: UploadQuota,
    private val s3Provider: ObjectProvider<S3Client>,
    @Value("\${app.admin.secret:}") private val adminSecret: String,
    @Value("\${app.upload.max-image-bytes:5242880}") private val maxImageBytes: Long,
    @Value("\${app.upload.max-video-bytes:10485760}") private val maxVideoBytes: Long
) {
    private val imageTypes = setOf("image/jpeg", "image/png", "image/gif", "image/webp")
    private val videoTypes = setOf("video/mp4", "video/webm")

    /** 게시판 첨부: 관리자만. 영구 보관되므로 가장 엄격하게 막습니다. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun upload(
        @RequestParam("file") file: MultipartFile,
        @RequestParam(name = "adminPassword", required = false) adminPasswordParam: String?,
        @RequestHeader(name = "X-Admin-Password", required = false) adminPasswordHeader: String?
    ): Map<String, String> {
        requireAdmin(adminPasswordParam ?: adminPasswordHeader)
        return store(file, props.keyPrefix, imageTypes + videoTypes)
    }

    /**
     * aicheck 분석용 임시 업로드: 인증 없이 열어둡니다.
     * tmp/ 로 올라가고 S3 라이프사이클 규칙이 1일 뒤 자동 삭제하므로
     * 저장 비용이 누적되지 않습니다. 이미지만 허용합니다.
     */
    @PostMapping("/temp")
    @ResponseStatus(HttpStatus.CREATED)
    fun uploadTemp(@RequestParam("file") file: MultipartFile): Map<String, String> =
        store(file, props.tempKeyPrefix, imageTypes)

    private fun requireAdmin(supplied: String?) {
        // 관리자 암호가 설정되지 않았으면 아무도 통과시키지 않습니다(fail closed).
        if (adminSecret.isBlank() || supplied.isNullOrEmpty() || !constantTimeEquals(supplied, adminSecret)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "업로드 권한이 없습니다")
        }
    }

    private fun store(file: MultipartFile, prefix: String, allowed: Set<String>): Map<String, String> {
        if (!props.enabled) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "이미지 업로드가 비활성화되어 있습니다")
        }
        val s3 = s3Provider.ifAvailable
            ?: throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "S3 클라이언트가 준비되지 않았습니다")

        if (file.isEmpty) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "빈 파일입니다")

        // 클라이언트가 보낸 Content-Type 은 마음대로 위조할 수 있으므로
        // 파일 앞부분(매직 바이트)을 직접 읽어 실제 형식을 판별합니다.
        val bytes = file.bytes
        val detected = sniff(bytes)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "허용되지 않는 파일 형식입니다")
        if (detected !in allowed) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "허용되지 않는 형식입니다 ($detected)")
        }

        val limit = if (detected in videoTypes) maxVideoBytes else maxImageBytes
        if (bytes.size > limit) {
            throw ResponseStatusException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "파일이 너무 큽니다 (최대 ${limit / 1024 / 1024}MB)"
            )
        }

        val size = bytes.size.toLong()
        if (!quota.tryReserve(size)) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "오늘 업로드 용량 한도에 도달했어요. 내일 다시 이용해주세요.")
        }

        val key = "$prefix${UUID.randomUUID()}.${extOf(detected)}"
        try {
            val req = PutObjectRequest.builder()
                .bucket(props.bucket)
                .key(key)
                .contentType(detected)
                .build()
            s3.putObject(req, RequestBody.fromBytes(bytes))
        } catch (e: Exception) {
            quota.release(size)
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "업로드에 실패했습니다")
        }

        val url = if (props.publicBaseUrl.isNotBlank())
            "${props.publicBaseUrl.trimEnd('/')}/$key"
        else
            "https://${props.bucket}.s3.${props.region}.amazonaws.com/$key"

        return mapOf("url" to url)
    }

    private fun extOf(contentType: String) = when (contentType) {
        "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/gif" -> "gif"
        "image/webp" -> "webp"; "video/mp4" -> "mp4"; "video/webm" -> "webm"
        else -> "bin"
    }

    /** 파일 시그니처로 실제 형식 판별. 모르는 형식이면 null. */
    private fun sniff(b: ByteArray): String? {
        fun at(offset: Int, vararg sig: Int): Boolean {
            if (b.size < offset + sig.size) return false
            return sig.withIndex().all { (i, v) -> (b[offset + i].toInt() and 0xFF) == v }
        }
        fun ascii(offset: Int, s: String): Boolean {
            if (b.size < offset + s.length) return false
            return s.indices.all { b[offset + it].toInt().toChar() == s[it] }
        }
        return when {
            at(0, 0xFF, 0xD8, 0xFF) -> "image/jpeg"
            at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> "image/gif"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
            at(0, 0x1A, 0x45, 0xDF, 0xA3) -> "video/webm"
            ascii(4, "ftyp") -> "video/mp4"
            else -> null
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray()
        val y = b.toByteArray()
        if (x.size != y.size) return false
        var diff = 0
        for (i in x.indices) diff = diff or (x[i].toInt() xor y[i].toInt())
        return diff == 0
    }
}
