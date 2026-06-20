package net.donjiral.pingpong.aicheck

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.math.abs

@Service
class AiCheckService(
    private val mapper: ObjectMapper,
    // 실제 탐지 제공사 연동 시 사용 (지금은 비어 있으면 데모 추정)
    @Value("\${app.aicheck.provider:}") private val provider: String,
    @Value("\${app.aicheck.api-url:}") private val apiUrl: String,
    @Value("\${app.aicheck.api-key:}") private val apiKey: String
) {
    private val http = HttpClient.newHttpClient()

    // 같은 영상 결과 캐시 (비용 절감 + 부하 완화). videoId -> (저장시각, 결과)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, AiCheckResponse>>()
    private val cacheTtlMs = 6 * 60 * 60 * 1000L // 6시간

    fun analyze(rawUrl: String): AiCheckResponse {
        val videoId = extractYoutubeId(rawUrl)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "유효한 유튜브 URL이 아니에요")

        cache[videoId]?.let { (ts, resp) ->
            if (System.currentTimeMillis() - ts < cacheTtlMs) return resp
        }
        if (cache.size > 5000) cache.clear()

        // 1) 공개 메타데이터 (키 불필요)
        var title: String? = null
        var author: String? = null
        var thumbnail: String? = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        try {
            val oembed = "https://www.youtube.com/oembed?format=json&url=" +
                URLEncoder.encode("https://www.youtube.com/watch?v=$videoId", "UTF-8")
            val res = http.send(
                HttpRequest.newBuilder(URI.create(oembed)).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            if (res.statusCode() == 200) {
                val node = mapper.readTree(res.body())
                title = node.get("title")?.asText()
                author = node.get("author_name")?.asText()
                node.get("thumbnail_url")?.asText()?.let { thumbnail = it }
            }
        } catch (_: Exception) { /* 메타데이터 실패는 무시 */ }

        // 2) 판별 (제공사 키가 있으면 실제 호출, 없으면 데모 추정)
        val configured = provider.isNotBlank() && apiUrl.isNotBlank() && apiKey.isNotBlank()
        val result = if (configured) {
            realProviderCheck(videoId, title, author, thumbnail)
        } else {
            demoCheck(videoId, title, author, thumbnail)
        }
        cache[videoId] = System.currentTimeMillis() to result
        return result
    }

    private fun realProviderCheck(
        videoId: String, title: String?, author: String?, thumbnail: String?
    ): AiCheckResponse {
        // 실제 연동 시: 영상 오디오/프레임 추출 → provider API 호출 → 확률 매핑
        // 제공사를 정하면 이 함수만 구현하면 됩니다.
        throw ResponseStatusException(
            HttpStatus.NOT_IMPLEMENTED,
            "탐지 제공사가 설정됐지만 연동 구현이 아직이에요. realProviderCheck()를 구현하세요."
        )
    }

    /** 데모: 영상 ID 기반 의사난수. 실제 탐지가 아니라 '추정 예시'입니다. */
    private fun demoCheck(
        videoId: String, title: String?, author: String?, thumbnail: String?
    ): AiCheckResponse {
        val h = abs(videoId.hashCode())
        val audio = 10 + (h % 80)            // 10~89
        val video = 10 + ((h / 7) % 80)      // 10~89
        val overall = (audio + video) / 2
        val verdict = when {
            overall >= 70 -> "AI 생성 가능성 높음 (추정)"
            overall >= 40 -> "판단 애매 / 혼합 가능성 (추정)"
            else -> "실제 촬영/녹음 가능성 높음 (추정)"
        }
        return AiCheckResponse(
            videoId = videoId, title = title, author = author, thumbnail = thumbnail,
            audioAiProbability = audio, videoAiProbability = video, overall = overall,
            verdict = verdict, demo = true,
            note = "⚠️ 데모 추정 결과입니다. 실제 AI 탐지가 아니며, 탐지 API를 연결하면 실제 분석으로 바뀝니다."
        )
    }

    private fun extractYoutubeId(url: String): String? {
        val patterns = listOf(
            Regex("""youtu\.be/([\w-]{11})"""),
            Regex("""[?&]v=([\w-]{11})"""),
            Regex("""youtube\.com/embed/([\w-]{11})"""),
            Regex("""youtube\.com/shorts/([\w-]{11})""")
        )
        for (p in patterns) p.find(url)?.let { return it.groupValues[1] }
        // 그냥 11자리 ID만 넣은 경우
        if (Regex("""^[\w-]{11}$""").matches(url.trim())) return url.trim()
        return null
    }
}
