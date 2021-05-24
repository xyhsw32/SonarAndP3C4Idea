package ex;

public class ProblemTreeNodeData {
    /**
     * 阻断
     */
    public static final String PROBLEM_TYPE_BLOCKER = "Blocker";

    /**
     * 严重
     */
    public static final String PROBLEM_TYPE_CRITICAL = "Critical";

    /**
     * 主要
     */
    public static final String PROBLEM_TYPE_MAJOR = "Major";

    /**
     * 一级节点
     */
    public static final String NODE_TYPE_PROBLEM_TYPE = "PROBLEM_TYPE";

    /**
     * 二级节点
     */
    public static final String NODE_TYPE_ALI_CHECK = "ALI_CHECK";

    /**
     * 三级节点
     */
    public static final String NODE_TYPE_CHECK_RULE = "CHECK_RULE";

    /**
     * 四级节点
     */
    public static final String NODE_TYPE_CHECK_FILE = "CHECK_FILE";

    /**
     * 五级节点
     */
    public static final String NODE_TYPE_CHECK_ISSUE = "CHECK_ISSUE";

    /**
     * 节点名称
     */
    private String name;

    /**
     * 问题类型
     */
    private String problemType;

    /**
     * 节点类型
     */
    private String nodeType;

    /**
     * 问题行号
     */
    private Integer problemFromLine;

    /**
     * 产生问题的文件相对路径
     */
    private String problemFromFilePath;

    /**
     * 产生问题的文件名称
     */
    private String problemFromFileName;

    /**
     * 问题描述模板
     */
    private String descriptionTemplate;

    /**
     * 文件所属语言名称
     */
    private String languageName;

    /**
     * 规则信息
     */
    private ScanRuleData scanRuleData;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getProblemType() {
        return problemType;
    }

    public void setProblemType(String problemType) {
        this.problemType = problemType;
    }

    public String getNodeType() {
        return nodeType;
    }

    public void setNodeType(String nodeType) {
        this.nodeType = nodeType;
    }

    public Integer getProblemFromLine() {
        return problemFromLine;
    }

    public void setProblemFromLine(Integer problemFromLine) {
        this.problemFromLine = problemFromLine;
    }

    public String getProblemFromFilePath() {
        return problemFromFilePath;
    }

    public void setProblemFromFilePath(String problemFromFilePath) {
        this.problemFromFilePath = problemFromFilePath;
    }

    public String getDescriptionTemplate() {
        return descriptionTemplate;
    }

    public void setDescriptionTemplate(String descriptionTemplate) {
        this.descriptionTemplate = descriptionTemplate;
    }

    public String getProblemFromFileName() {
        return problemFromFileName;
    }

    public void setProblemFromFileName(String problemFromFileName) {
        this.problemFromFileName = problemFromFileName;
    }

    public String getLanguageName() {
        return languageName;
    }

    public void setLanguageName(String languageName) {
        this.languageName = languageName;
    }

    public ScanRuleData getScanRuleData() {
        return scanRuleData;
    }

    public void setScanRuleData(ScanRuleData scanRuleData) {
        this.scanRuleData = scanRuleData;
    }
}
