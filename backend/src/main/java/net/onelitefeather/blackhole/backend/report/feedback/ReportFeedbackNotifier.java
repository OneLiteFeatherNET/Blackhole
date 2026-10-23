package net.onelitefeather.blackhole.backend.report.feedback;

/**
 * Pokes the proxies that a reporter has a message waiting, so an online reporter is told at once.
 * The poke carries only the reporter's owner hash; the proxy fetches and acknowledges the message
 * through the API, so a lost poke only means the reporter hears at their next login.
 */
public interface ReportFeedbackNotifier {

    void queued(String reporterHash);
}
