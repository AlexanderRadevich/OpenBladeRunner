package com.cutter.plotterctl;

public class CutJob {
    public enum State { IDLE, READY, FEEDING, CUTTING, SENT, COMPLETE, ERROR }

    private volatile State state = State.IDLE;
    private volatile String hpglData;
    private volatile String fileName;
    private volatile int totalChunks;
    private volatile int sentChunks;
    private volatile int speed = 2;
    private volatile int force = 2;
    private volatile String errorMessage;

    public State getState() { return state; }
    public void setState(State s) { this.state = s; }
    public String getHpglData() { return hpglData; }
    public void setHpglData(String d) { this.hpglData = d; }
    public String getFileName() { return fileName; }
    public void setFileName(String n) { this.fileName = n; }
    public int getTotalChunks() { return totalChunks; }
    public void setTotalChunks(int t) { this.totalChunks = t; }
    public int getSentChunks() { return sentChunks; }
    public void setSentChunks(int s) { this.sentChunks = s; }
    public int getSpeed() { return speed; }
    public void setSpeed(int s) { this.speed = Math.max(1, Math.min(10, s)); }
    public int getForce() { return force; }
    public void setForce(int f) { this.force = Math.max(1, Math.min(10, f)); }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String e) { this.errorMessage = e; }

    public int getProgress() {
        if (totalChunks <= 0) return 0;
        return (sentChunks * 100) / totalChunks;
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"state\":\"").append(state.name()).append("\"");
        sb.append(",\"fileName\":\"").append(esc(fileName)).append("\"");
        sb.append(",\"progress\":").append(getProgress());
        sb.append(",\"totalChunks\":").append(totalChunks);
        sb.append(",\"sentChunks\":").append(sentChunks);
        sb.append(",\"speed\":").append(speed);
        sb.append(",\"force\":").append(force);
        if (errorMessage != null) {
            sb.append(",\"error\":\"").append(esc(errorMessage)).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
