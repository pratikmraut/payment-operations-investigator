import { EvidenceLibraryPage } from "./EvidenceLibrary";
import { UatEvidencePage } from "./UatEvidence";
import { EvidenceQuestions } from "./EvidenceQuestions";
import { navigateLink } from "./routing";
import { Files, MessageSquare } from "lucide-react";
import type { User } from "./types";

export function EvidenceWorkspace({
  user,
  view,
  caseId,
  evidenceId,
}: {
  user: User;
  view?: "questions" | "exports";
  caseId?: string;
  evidenceId?: string;
}) {
  const exports = view === "exports";
  const questions = view === "questions";
  return (
    <>
      <nav className="evidence-workspace-nav" aria-label="Evidence workspace">
        <a
          href="/evidences"
          onClick={navigateLink}
          aria-current={!view ? "page" : undefined}
        >
          <Files size={17} aria-hidden="true" />
          Case evidence
        </a>
        <a
          href="/evidences/questions"
          onClick={navigateLink}
          aria-current={questions ? "page" : undefined}
        >
          <MessageSquare size={17} aria-hidden="true" />
          Evidence Q&amp;A
        </a>
      </nav>
      {exports ? (
        <>
          <p className="evidence-workspace-context">
            Review separately staged exports and their saved model answers here.
            For saved payment cases, select a case and an evidence version in
            the Evidence Q&amp;A tab.
          </p>
          <UatEvidencePage user={user} />
        </>
      ) : questions ? (
        <EvidenceQuestions
          user={user}
          caseId={caseId}
          evidenceId={evidenceId}
        />
      ) : (
        <EvidenceLibraryPage user={user} />
      )}
    </>
  );
}
