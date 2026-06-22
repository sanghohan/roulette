package net.donjiral.pingpong.visit

import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

@Entity
@Table(name = "visit_counter")
class VisitCounter(
    @Id var id: Int = 1,
    var total: Long = 0
)

@Entity
@Table(name = "visit_day")
class VisitDay(
    // day, count 는 SQL 예약어라 컬럼명을 따로 지정
    @Id @Column(name = "ymd") var day: String = "",
    @Column(name = "cnt") var count: Long = 0
)

interface VisitCounterRepository : JpaRepository<VisitCounter, Int>
interface VisitDayRepository : JpaRepository<VisitDay, String>

@Service
class VisitService(
    private val counterRepo: VisitCounterRepository,
    private val dayRepo: VisitDayRepository
) {
    @Transactional(readOnly = true)
    fun get(): Map<String, Long> {
        val total = counterRepo.findById(1).map { it.total }.orElse(0)
        val today = dayRepo.findById(LocalDate.now().toString()).map { it.count }.orElse(0)
        return mapOf("total" to total, "today" to today)
    }

    @Transactional
    fun hit(): Map<String, Long> {
        val c = counterRepo.findById(1).orElseGet { VisitCounter(1, 0) }
        c.total += 1
        counterRepo.save(c)
        val key = LocalDate.now().toString()
        val d = dayRepo.findById(key).orElseGet { VisitDay(key, 0) }
        d.count += 1
        dayRepo.save(d)
        return mapOf("total" to c.total, "today" to d.count)
    }
}

@RestController
@RequestMapping("/api/visits")
class VisitController(private val service: VisitService) {
    @GetMapping
    fun get() = service.get()

    @PostMapping
    fun hit() = service.hit()
}
