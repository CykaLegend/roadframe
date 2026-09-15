package be.roadframe.coach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShotCategoryTest {
    @Test
    fun everyShotHasACompleteBrief() {
        ShotCategory.entries.forEach { shot ->
            assertTrue(shot.name, shot.brief.why.isNotBlank())
            assertTrue(shot.name, shot.brief.park.isNotBlank())
            assertTrue(shot.name, shot.brief.stand.isNotBlank())
            assertTrue(shot.name, shot.brief.checklist.size >= 4)
            assertTrue(shot.name, shot.recommendedZoom >= 0.6f)
        }
    }

    @Test
    fun numbersAreUniqueAndOrdered() {
        val numbers = ShotCategory.entries.map { it.number }
        assertEquals(numbers.sorted(), numbers)
        assertEquals(numbers.size, numbers.toSet().size)
    }

    @Test
    fun trackedShotsHaveGeometryAndDetailsDoNot() {
        ShotCategory.entries.filter { it.geometry.tracksVehicle }.forEach {
            assertTrue(it.name, it.geometry.fill in 0.1f..0.95f)
        }
        assertTrue(ShotCategory.WHEEL.geometry.viewingAngles.isEmpty())
        assertTrue(ShotCategory.ROLLING.geometry.viewingAngles.isEmpty())
    }

    @Test
    fun everyStructureOrdersEveryRuleOnce() {
        CoachStructure.entries.forEach { structure ->
            assertEquals(structure.name, Rule.entries.size, structure.order.size)
            assertEquals(structure.name, Rule.entries.toSet(), structure.order.toSet())
        }
    }
}
