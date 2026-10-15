package net.donjiral.pingpong.post

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * 2026-10: '돈지랄 앱 게시판'(board=dev)을 앱별 게시판(chamedu · nowplayingcd · maskfall)으로 나눴다.
 * 옛 dev 글을 각 앱 게시판으로 옮긴다. board 가 아직 dev 인 글만 건드리므로 몇 번 돌아도 결과가 같다.
 * 사이트의 /dev/ 페이지도 같은 짝으로 옛 링크(/dev/?post=번호)를 새 주소로 보낸다.
 * 129 [🛸 퀀텀 파이터] 소개는 내린 웹판 안내라 옮기지 않는다.
 */
@Component
class BoardMigration(private val postRepository: PostRepository) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(BoardMigration::class.java)

    private val moves = mapOf(
        68L to "chamedu",       // [참교육] 📱 사용 설명서
        70L to "chamedu",       // [참교육] 앱 테스트터 리뷰를 올려주세요
        71L to "chamedu",       // [참교육] 앱 소개
        130L to "nowplayingcd"  // [💿 Now Playing CD] 앱 소개 · 사용 설명서
    )

    @Transactional
    override fun run(args: ApplicationArguments) {
        postRepository.findAllById(moves.keys).filter { it.board == "dev" }.forEach {
            it.board = moves.getValue(it.id)
            log.info("게시판 이전: 글 {} dev → {}", it.id, it.board)
        }
    }
}
