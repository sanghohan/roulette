package net.donjiral.pingpong.ytsummary

import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.*

data class YtSummaryRequest(
    @field:NotBlank val url: String
)

data class YtSummaryResponse(
    val videoId: String,
    val title: String?,
    val author: String?,
    val thumbnail: String?,
    val language: String?,     // 사용된 자막 언어 코드
    val summary: String,       // 요약 본문
    val cached: Boolean,       // 캐시 응답 여부
    val transcriptChars: Int   // 요약에 사용된 자막 글자 수
)

@RestController
@RequestMapping("/api/ytsummary")
class YtSummaryController(
    private val service: YtSummaryService
) {
    @PostMapping
    fun summarize(@RequestBody req: YtSummaryRequest): YtSummaryResponse = service.summarize(req.url)
}
