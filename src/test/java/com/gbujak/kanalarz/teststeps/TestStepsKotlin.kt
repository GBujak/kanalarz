package com.gbujak.kanalarz.teststeps

import com.gbujak.kanalarz.annotations.Rollback
import com.gbujak.kanalarz.annotations.Step
import com.gbujak.kanalarz.annotations.StepsComponent

@StepsComponent(identifier = "test-steps-kotlin")
open class TestStepsKotlin {

    @Step(identifier = "uppercase-step", fallible = true)
    open fun uppercaseStep(param: String): String =
        param.uppercase()

    @Rollback(forStep = "uppercase-step")
    open fun uppercaseRollback() { }

}