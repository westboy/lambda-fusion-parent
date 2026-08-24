package com.lambda.fusion.ai.runtime.tools;

import com.lambda.fusion.ai.runtime.annotaion.AiTools;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.ToolSuspendException;
import java.util.List;

/**
 * 用户交互输入工具：单选、多选与文本补充输入。
 *
 * <p>三个工具均声明为 {@code externalTool}：Toolkit 跳过本地执行，AgentScope 以
 * {@code TOOL_SUSPENDED} 挂起运行，挂起信号经根 {@code AgentResultEvent} 进入事件流
 * （2.0.1 不发出 {@code RequireExternalExecutionEvent}），由业务层映射为 {@code input_required}
 * 中断交给前端渲染交互卡片；用户提交的选择或文本作为工具结果回传后，Agent 从挂起点直接续跑。
 * 方法体不会被框架调用，仅以 {@link ToolSuspendException} 兜底直接误调用。
 *
 * @author Jin
 */
@AiTools
public class UserInteractionTools {

    /** 单选工具名。 */
    public static final String SINGLE_CHOICE_TOOL = "ask_single_choice";

    /** 多选工具名。 */
    public static final String MULTI_CHOICE_TOOL = "ask_multi_choice";

    /** 文本输入工具名。 */
    public static final String TEXT_INPUT_TOOL = "ask_text_input";

    @Tool(
            name = SINGLE_CHOICE_TOOL,
            description = "Ask the user to pick exactly one option from a list. "
                    + "The call suspends until the user submits a choice; the selected option "
                    + "is returned as the tool result.")
    public String askSingleChoice(
            @ToolParam(name = "question", description = "Question shown to the user") String question,
            @ToolParam(name = "options", description = "Selectable options, 2-8 items with short distinct labels")
                    List<String> options) {
        throw new ToolSuspendException();
    }

    @Tool(
            name = MULTI_CHOICE_TOOL,
            description = "Ask the user to pick one or more options from a list. "
                    + "The call suspends until the user submits the selection; the selected "
                    + "options are returned as the tool result.")
    public String askMultiChoice(
            @ToolParam(name = "question", description = "Question shown to the user") String question,
            @ToolParam(name = "options", description = "Selectable options, 2-8 items with short distinct labels")
                    List<String> options,
            @ToolParam(
                            name = "minSelect",
                            description = "Minimum number of options the user must select",
                            required = false)
                    Integer minSelect,
            @ToolParam(
                            name = "maxSelect",
                            description = "Maximum number of options the user can select",
                            required = false)
                    Integer maxSelect) {
        throw new ToolSuspendException();
    }

    @Tool(
            name = TEXT_INPUT_TOOL,
            description = "Ask the user to provide free-form text input. "
                    + "The call suspends until the user submits the answer, which is returned "
                    + "as the tool result.")
    public String askTextInput(
            @ToolParam(name = "question", description = "Question shown to the user") String question,
            @ToolParam(name = "maxLength", description = "Maximum length of the answer in characters", required = false)
                    Integer maxLength) {
        throw new ToolSuspendException();
    }
}
