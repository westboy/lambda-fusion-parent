package com.lambda.fusion.ai.skill.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.lambda.fusion.ai.skill.model.SkillView;
import io.agentscope.core.skill.AgentSkill;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 验证 {@link SkillServiceImpl#toAgentSkill} / {@link SkillServiceImpl#toView} 的 DTO↔AgentSkill 映射。
 *
 * @author Jin
 */
class SkillDtoConverterTest {

    @Test
    void toAgentSkillMapsFields() {
        AgentSkill skill = SkillServiceImpl.toAgentSkill("s", "d", "# md", Map.of("r.md", "# r"));

        assertThat(skill.getName()).isEqualTo("s");
        assertThat(skill.getDescription()).isEqualTo("d");
        assertThat(skill.getSkillContent()).isEqualTo("# md");
        assertThat(skill.getResources()).containsEntry("r.md", "# r");
        assertThat(skill.getSource()).isEqualTo("marketplace");
    }

    @Test
    void toAgentSkillNullResourcesYieldsEmptyMap() {
        AgentSkill skill = SkillServiceImpl.toAgentSkill("s", "d", "md", null);

        assertThat(skill.getResources()).isEmpty();
    }

    @Test
    void toViewMapsFields() {
        AgentSkill skill = SkillServiceImpl.toAgentSkill("s", "d", "md", Map.of("r", "c"));

        SkillView view = SkillServiceImpl.toView(skill);

        assertThat(view.getName()).isEqualTo("s");
        assertThat(view.getDescription()).isEqualTo("d");
        assertThat(view.getMarkdown()).isEqualTo("md");
        assertThat(view.getResources()).containsEntry("r", "c");
        assertThat(view.getResourceCount()).isEqualTo(1);
        assertThat(view.getResourceFiles()).singleElement().satisfies(resource -> {
            assertThat(resource.getPath()).isEqualTo("r");
            assertThat(resource.getContent()).isNull();
            assertThat(resource.getContentLength()).isEqualTo(1);
            assertThat(resource.isBinary()).isFalse();
        });
    }

    @Test
    void toViewCanOmitResourceContent() {
        AgentSkill skill = SkillServiceImpl.toAgentSkill("s", "d", "md", Map.of("r", "content"));

        SkillView view = SkillServiceImpl.toView(skill, false);

        assertThat(view.getMarkdown()).isEqualTo("md");
        assertThat(view.getResources()).isNull();
        assertThat(view.getResourceFiles()).singleElement().satisfies(resource -> {
            assertThat(resource.getPath()).isEqualTo("r");
            assertThat(resource.getContent()).isNull();
            assertThat(resource.getContentLength()).isEqualTo(7);
        });
    }

    @Test
    void toSummaryOmitsResourceContent() {
        AgentSkill skill = SkillServiceImpl.toAgentSkill("s", "d", "md", Map.of("r", "c"));

        SkillView view = SkillServiceImpl.toSummary(skill);

        assertThat(view.getName()).isEqualTo("s");
        assertThat(view.getDescription()).isEqualTo("d");
        assertThat(view.getResourceCount()).isEqualTo(1);
        assertThat(view.getResources()).isNull();
        assertThat(view.getMarkdown()).isNull();
    }
}
