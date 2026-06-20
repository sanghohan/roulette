package net.donjiral.pingpong.aicheck

import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.*

data class AiCheckRequest(
    @field:NotBlank val url: String
)

data class AiCheckResponse(
    val videoId: String,
    val title: String?,
    val author: String?,
    val thumbnail: String?,
    val audioAiProbability: Int,   // 0~100
    val videoAiProbability: Int,   // 0~100
    val overall: Int,              // 0~100
    val verdict: String,           // 텍스트 판정
    val demo: Boolean,             // true면 실제 탐지 아님(데모 추정)
    val note: String
)

@RestController
@RequestMapping("/api/aicheck")
class AiCheckController(
    private val service: AiCheckService
) {
    @PostMapping
    fun check(@RequestBody req: AiCheckRequest): AiCheckResponse = service.analyze(req.url)
}
