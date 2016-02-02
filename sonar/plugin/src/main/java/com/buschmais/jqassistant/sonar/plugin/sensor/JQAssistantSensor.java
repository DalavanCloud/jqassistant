package com.buschmais.jqassistant.sonar.plugin.sensor;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.transform.stream.StreamSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.Phase;
import org.sonar.api.batch.Sensor;
import org.sonar.api.batch.SensorContext;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.component.ResourcePerspectives;
import org.sonar.api.config.Settings;
import org.sonar.api.issue.Issuable;
import org.sonar.api.platform.ComponentContainer;
import org.sonar.api.profiles.RulesProfile;
import org.sonar.api.resources.Project;
import org.sonar.api.resources.Resource;
import org.sonar.api.rules.ActiveRule;

import com.buschmais.jqassistant.core.report.schema.v1.ColumnHeaderType;
import com.buschmais.jqassistant.core.report.schema.v1.ColumnType;
import com.buschmais.jqassistant.core.report.schema.v1.ColumnsHeaderType;
import com.buschmais.jqassistant.core.report.schema.v1.ConceptType;
import com.buschmais.jqassistant.core.report.schema.v1.ConstraintType;
import com.buschmais.jqassistant.core.report.schema.v1.ElementType;
import com.buschmais.jqassistant.core.report.schema.v1.GroupType;
import com.buschmais.jqassistant.core.report.schema.v1.JqassistantReport;
import com.buschmais.jqassistant.core.report.schema.v1.ObjectFactory;
import com.buschmais.jqassistant.core.report.schema.v1.ResultType;
import com.buschmais.jqassistant.core.report.schema.v1.RowType;
import com.buschmais.jqassistant.core.report.schema.v1.RuleType;
import com.buschmais.jqassistant.core.report.schema.v1.SourceType;
import com.buschmais.jqassistant.core.report.schema.v1.StatusEnumType;
import com.buschmais.jqassistant.sonar.plugin.JQAssistant;

/**
 * {@link Sensor} implementation scanning for jqassistant-report.xml files.
 */
@Phase(name = Phase.Name.DEFAULT)
public class JQAssistantSensor implements Sensor {

    private static final Logger LOGGER = LoggerFactory.getLogger(JQAssistantSensor.class);

    private final Settings settings;

    private final FileSystem fileSystem;

    private final ResourcePerspectives perspectives;

    private final Map<String, LanguageResourceResolver> languageResourceResolvers;

    private final Map<String, ActiveRule> rules;

    private final JAXBContext reportContext;

    public JQAssistantSensor(RulesProfile profile, ResourcePerspectives perspectives, ComponentContainer componentContainerc, Settings settings,
    		FileSystem moduleFileSystem) throws JAXBException {
        this.settings = settings;
        this.fileSystem = moduleFileSystem;
        this.perspectives = perspectives;
        this.languageResourceResolvers = new HashMap<>();
        for (LanguageResourceResolver resolver : componentContainerc.getComponentsByType(LanguageResourceResolver.class)) {
            languageResourceResolvers.put(resolver.getLanguage(), resolver);
        }
        LOGGER.info("Found {} language resource resolvers.", languageResourceResolvers.size());
        this.rules = new HashMap<>();
        for (ActiveRule activeRule : profile.getActiveRulesByRepository(JQAssistant.KEY)) {
            rules.put(activeRule.getRule().getName(), activeRule);
        }
        this.reportContext = JAXBContext.newInstance(ObjectFactory.class);
    }

    @Override
    public void analyse(Project project, SensorContext sensorContext) {
        File reportFile = getReportFile();
        if (reportFile != null) {
            JqassistantReport report = readReport(reportFile);
            evaluateReport(project, sensorContext, report);
        }
    }

    public boolean shouldExecuteOnProject(Project project) {
        return true;
    }

    @Override
    public String toString() {
        return "jQAssistant Sensor";
    }

    private JqassistantReport readReport(File reportFile) {
        try {
            Unmarshaller unmarshaller = reportContext.createUnmarshaller();
            return unmarshaller.unmarshal(new StreamSource(reportFile), JqassistantReport.class).getValue();
        } catch (JAXBException e) {
            throw new IllegalStateException("Cannot read jQAssistant report from file " + reportFile, e);
        }
    }

    private void evaluateReport(Project project, SensorContext sensorContext, JqassistantReport report) {
        for (GroupType groupType : report.getGroup()) {
            LOGGER.info("Processing group '{}'", groupType.getId());
            for (RuleType ruleType : groupType.getConceptOrConstraint()) {
                String id = ruleType.getId();
                ActiveRule activeRule = rules.get(id);
                if (activeRule == null) {
                    LOGGER.warn("Cannot resolve activeRule for id '{}'.", id);
                } else {
                    ResultType result = ruleType.getResult();
                    String primaryColumn = getPrimaryColumn(result);
                    StatusEnumType status = ruleType.getStatus();
                    if (StatusEnumType.FAILURE.equals(status)) {
                        if (ruleType instanceof ConceptType) {
                            createIssue(project, null, "The concept could not be applied.", activeRule, sensorContext);
                        } else if (ruleType instanceof ConstraintType) {
                            for (RowType rowType : result.getRows().getRow()) {
                                Resource resource = null;
                                Integer lineNumber = null;
                                StringBuilder message = new StringBuilder();
                                for (ColumnType column : rowType.getColumn()) {
                                    String name = column.getName();
                                    String value = column.getValue();
                                    if (name.equals(primaryColumn)) {
                                        ElementType languageElement = column.getElement();
                                        if (languageElement != null) {
                                            LanguageResourceResolver resourceResolver = languageResourceResolvers.get(languageElement.getLanguage());
                                            if (resourceResolver != null) {
                                                String element = languageElement.getValue();
                                                resource = resourceResolver.resolve(element, value);
                                            }
                                        }
                                        SourceType source = column.getSource();
                                        lineNumber = source.getLine();
                                    }
                                    if (message.length() > 0) {
                                        message.append(", ");
                                    }
                                    message.append(name);
                                    message.append('=');
                                    message.append(value);
                                }
                                String issueDescription = ruleType.getDescription() + "\n" + message.toString();
                                createIssue(resource != null ? resource : project, lineNumber, issueDescription, activeRule, sensorContext);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Determine the primary column from the result, i.e. the column which
     * contains the resource to create an issue for.
     * 
     * @param result
     *            The result.
     * @return The name of the primary column or <code>null</code>.
     */
    private String getPrimaryColumn(ResultType result) {
        if (result != null) {
            ColumnsHeaderType columns = result.getColumns();
            for (ColumnHeaderType columnHeaderType : columns.getColumn()) {
                if (columnHeaderType.isPrimary()) {
                    return columnHeaderType.getValue();
                }
            }
        }
        return null;
    }

    /**
     * Creates an issue.
     * 
     * @param resource
     *            The resource to create the issue for.
     * @param message
     *            The message to use.
     * @param rule
     *            The rule which has been violated.
     * @param sensorContext
     *            The sensor context.
     */
    private void createIssue(Resource resource, Integer lineNumber, String message, ActiveRule rule, SensorContext sensorContext) {
        Issuable issuable;
        if (sensorContext.getResource(resource) != null) {
            issuable = perspectives.as(Issuable.class, resource);
            Issuable.IssueBuilder issueBuilder = issuable.newIssueBuilder().ruleKey(rule.getRule().ruleKey()).message(message);
            if (lineNumber != null) {
                issueBuilder.line(lineNumber);
            }
            issuable.addIssue(issueBuilder.build());
            LOGGER.info("Issue added for resource '{}'.", resource.getLongName());
        } else {
            LOGGER.warn("Resource '{}' not found, issue not created.", resource.getLongName());
        }
    }

    /**
     * Return the report xml file or null if not found. Checks whether
     * {@link JQAssistant#SETTINGS_KEY_REPORT_PATH} is set or not and looks up
     * the passed path or the default build directory.
     * 
     * @return reportFile File object of report xml or null if not found.
     */
    private File getReportFile() {
        File reportFile;

        String configReportPath = settings.getString(JQAssistant.SETTINGS_KEY_REPORT_PATH);
        if (configReportPath != null && !configReportPath.isEmpty()) {
            LOGGER.info("Using setting '{}' = '{}' to find report file.", JQAssistant.SETTINGS_KEY_REPORT_PATH, configReportPath);
            reportFile = new File(configReportPath);
        } else {
        	//resolve complete relative path with one step to ensure testability (mocking of filesystem)
            reportFile = fileSystem.resolvePath("target/"+JQAssistant.REPORT_FILE_NAME);
        }

        if (reportFile.exists()) {
            LOGGER.info("Report found at '{}'.", reportFile.getAbsolutePath());
            return reportFile;
        } else {
            LOGGER.info("No report found at '{}'.", reportFile.getAbsolutePath());
            return null;
        }
    }
}
