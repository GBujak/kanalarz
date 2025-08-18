package com.gbujak.kanalarz.teststeps

import com.gbujak.kanalarz.annotations.Rollback
import com.gbujak.kanalarz.annotations.Step
import com.gbujak.kanalarz.annotations.StepsHolder
import org.springframework.stereotype.Component

@Component
@StepsHolder(identifier = "test-steps-kotlin")
open class TestStepsKotlin {

    @Step(identifier = "uppercase-step")
    open fun uppercaseStep(param: String): String =
        param.uppercase()

    @Step(identifier = "nullable-uppercase-step")
    open fun uppercaseStepNullable(paramNullable: String?): String? =
        paramNullable?.uppercase()

    @Rollback(forStep = "uppercase-step")
    open fun uppercaseRollback() { }

}