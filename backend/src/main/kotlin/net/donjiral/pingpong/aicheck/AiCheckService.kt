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
import java.time.Duration
import kotlin.math.abs

@Service
class AiCheckService(
    private val mapper: ObjectMapper,
    @Value("\${app.aicheck.provider:}") private val provider: String,
    @Value("\${app.aicheck.api-url:https://api.replicate.com/v1}") private val apiUrl: String,
    @Value("\${app.aicheck.api-key:}") private val apiKey: String,
    // 비전 모델 (owner/name 형식). 다른 모델로 바꾸려면 환경변수 AICHECK_IMAGE_MODEL 설정
    @Value("\${app.aicheck.image-model:yorickvp/llava-13b}") private val imageModel: String
) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, AiCheckResponse>>()
    private val cacheTtlMs = 6 * 60 * 60 * 1000L

    private val prompt =
        "You are an expert AI-generated image detector. This image is a frame from a video. " +
        "Respond with ONLY a single integer from 0 to 100 indicating the probability (percent) " +
        "that this frame was generated or heavily manipulated by AI. No words, just the number."

    fun analyze(rawUrl: String): AiCheckResponse {
        val videoId = extractYoutubeId(rawUrl)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "유효한 유튜브 URL이 아니에요")

        cache[videoId]?.let { (ts, resp) -> if (System.currentTimeMillis() - ts < cacheTtlMs) return resp }
        if (cache.size > 5000) cache.clear()

        val (title, author, thumbnail) = fetchMeta(videoId)

        val configured = provider.isNotBlank() && apiKey.isNotBlank()
        val result = if (configured) realCheck(videoId, title, author, thumbnail)
                     else demoCheck(videoId, title, author, thumbnail)
        cache[videoId] = System.currentTimeMillis() to result
        return result
    }

    // ---- 유튜브 공개 메타데이터 ----
    private fun fetchMeta(videoId: String): Triple<String?, String?, String?> {
        var title: String? = null; var author: String? = null
        var thumb: String? = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        try {
            val oembed = "https://www.youtube.com/oembed?format=json&url=" +
                URLEncoder.encode("https://www.youtube.com/watch?v=$videoId", "UTF-8")
            val res = http.send(HttpRequest.newBuilder(URI.create(oembed)).GET().build(),
                HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() == 200) {
                val n = mapper.readTree(res.body())
                title = n.get("title")?.asText(); author = n.get("author_name")?.asText()
                n.get("thumbnail_url")?.asText()?.let { thumb = it }
            }
        } catch (_: Exception) {}
        return Triple(title, author, thumb)
    }

    // ---- 실제 판별 (영상 프레임 → Replicate 비전 모델) ----
    private fun realCheck(videoId: String, title: String?, author: String?, thumbnail: String?): AiCheckResponse {
        // 유튜브가 제공하는 실제 영상 프레임 3장 (시작/중간/끝 부근)
        val frames = listOf(1, 2, 3).map { "https://i.ytimg.com/vi/$videoId/$it.jpg" }
        val scores = mutableListOf<Int>()
        for (f in frames) {
            try { askVisionModel(f)?.let { scores.add(it) } } catch (_: Exception) {}
        }
        if (scores.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "영상 분석에 실패했어요. 잠시 후 다시 시도해주세요.")
        }
        val video = scores.average().toInt().coerceIn(0, 100)
        return AiCheckResponse(
            videoId = videoId, title = title, author = author, thumbnail = thumbnail,
            videoAiProbability = video, audioSupported = false,
            verdict = verdictText(video), demo = false,
            note = "영상 프레임 ${scores.size}장을 AI 모델이 분석한 추정치예요. 참고용이며 100% 정확하지 않습니다. (오디오/음원 판별은 현재 지원하지 않아요)"
        )
    }

    /** Replicate 비전 모델 호출. Prefer:wait 로 동기 응답 받음. 0~100 정수 반환 */
    private fun askVisionModel(imageUrl: String): Int? {
        val (owner, name) = imageModel.split("/").let {
            if (it.size != 2) throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "AICHECK_IMAGE_MODEL 형식 오류")
            it[0] to it[1]
        }
        val endpoint = "$apiUrl/models/$owner/$name/predictions"
        val body = mapper.writeValueAsString(
            mapOf("input" to mapOf("image" to imageUrl, "prompt" to prompt))
        )
        val req = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(90))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Prefer", "wait")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() !in 200..299) return null
        val node = mapper.readTree(res.body())
        if (node.get("status")?.asText() != "succeeded") return null
        // output: 문자열 또는 문자열 배열
        val out = node.get("output")
        val text = when {
            out == null -> ""
            out.isArray -> out.joinToString("") { it.asText() }
            else -> out.asText()
        }
        val m = Regex("""\b(100|\d{1,2})\b""").find(text) ?: return null
        return m.groupValues[1].toIntOrNull()?.coerceIn(0, 100)
    }

    private fun verdictText(p: Int) = when {
        p >= 70 -> "AI 생성 가능성 높음 (추정)"
        p >= 40 -> "판단 애매 / 혼합 가능성 (추정)"
        else -> "실제 촬영 가능성 높음 (추정)"
    }

    // ---- 데모 (키 미설정 시) ----
    private fun demoCheck(videoId: String, title: String?, author: String?, thumbnail: String?): AiCheckResponse {
        val h = abs(videoId.hashCode())
        val video = 10 + (h % 80)
        return AiCheckResponse(
            videoId = videoId, title = title, author = author, thumbnail = thumbnail,
            videoAiProbability = video, audioSupported = false,
            verdict = verdictText(video), demo = true,
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
        if (Regex("""^[\w-]{11}$""").matches(url.trim())) return url.trim()
        return null
    }
}
