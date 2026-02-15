package org.example.quantapi.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Map;

@Document(collection = "strategy_workflows")
public class StrategyWorkflow {

    @Id
    private String id;

    @Indexed(unique = true)
    private String strategyId;

    private String workflowId;
    private Map<String, Object> workflowSpec;
    private Object tasks;
    private String xml;
    private String savedBy;
    private String savedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getStrategyId() {
        return strategyId;
    }

    public void setStrategyId(String strategyId) {
        this.strategyId = strategyId;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    public Map<String, Object> getWorkflowSpec() {
        return workflowSpec;
    }

    public void setWorkflowSpec(Map<String, Object> workflowSpec) {
        this.workflowSpec = workflowSpec;
    }

    public Object getTasks() {
        return tasks;
    }

    public void setTasks(Object tasks) {
        this.tasks = tasks;
    }

    public String getXml() {
        return xml;
    }

    public void setXml(String xml) {
        this.xml = xml;
    }

    public String getSavedBy() {
        return savedBy;
    }

    public void setSavedBy(String savedBy) {
        this.savedBy = savedBy;
    }

    public String getSavedAt() {
        return savedAt;
    }

    public void setSavedAt(String savedAt) {
        this.savedAt = savedAt;
    }
}
