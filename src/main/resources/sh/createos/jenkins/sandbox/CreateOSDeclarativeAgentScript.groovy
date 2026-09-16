package sh.createos.jenkins.sandbox

import java.util.UUID
import org.jenkinsci.plugins.pipeline.modeldefinition.agent.CheckoutScript
import org.jenkinsci.plugins.pipeline.modeldefinition.agent.DeclarativeAgentScript2
import org.jenkinsci.plugins.workflow.cps.CpsScript

class CreateOSDeclarativeAgentScript extends DeclarativeAgentScript2<CreateOSDeclarativeAgent> {
    CreateOSDeclarativeAgentScript(CpsScript s, CreateOSDeclarativeAgent a) {
        super(s, a)
    }

    @Override
    void run(Closure body) {
        String label = generatedLabel()
        CreateOSPipelineSupport.register(describable, label)
        try {
            script.node(label) {
                CheckoutScript.doCheckout2(script, describable, null) {
                    body.call()
                }
            }
        } finally {
            CreateOSPipelineSupport.unregister(label)
        }
    }

    private String generatedLabel() {
        String jobName = "${script.env.JOB_NAME ?: 'createos'}"
        String buildNumber = "${script.env.BUILD_NUMBER ?: 'run'}"
        String suffix = UUID.randomUUID().toString().substring(0, 8)
        String raw = "createos-${jobName}-${buildNumber}-${suffix}".toLowerCase()
        return raw.replaceAll("[^a-z0-9_.-]", "-")
    }
}
