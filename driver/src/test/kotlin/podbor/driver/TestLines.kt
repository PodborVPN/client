package podbor.driver

import arrow.core.nonEmptyListOf
import podbor.core.Handshake
import podbor.core.LineObservation
import podbor.core.Reach
import podbor.core.Sample
import podbor.core.Stage

/** Раунд линии для тестов края (копия помощника из core): Up — три рукопожатия, Down — три SYN без ответа за 1,5 с. */
fun line(russia: Reach, abroad: Reach): LineObservation {
    fun side(prefix: String, r: Reach) = nonEmptyListOf(0, 1, 2).map { i ->
        Sample("$prefix$i", if (r == Reach.Up) Handshake.Ok(connectMs = 30) else Handshake.Silent(Stage.Connect, waitedMs = 1_500))
    }
    return LineObservation(side("ru", russia), side("far", abroad))
}
