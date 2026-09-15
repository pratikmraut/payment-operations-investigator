import { FileText, MessageSquare, UsersRound } from "lucide-react";

const sections = [
  {
    id: "case-management-title",
    label: "Manage case",
    description: "Owner, notes & review",
    icon: UsersRound,
  },
  {
    id: "case-evidence-title",
    label: "Collect evidence",
    description: "API, Excel or JSON",
    icon: FileText,
  },
  {
    id: "case-investigation-title",
    label: "Investigate",
    description: "Questions & saved answers",
    icon: MessageSquare,
  },
];

export function CasePageNavigation() {
  function jump(id: string) {
    const heading = document.getElementById(id);
    if (!heading) return;
    heading.setAttribute("tabindex", "-1");
    heading.focus({ preventScroll: true });
    heading.scrollIntoView({ block: "start" });
  }
  return (
    <nav className="case-page-navigation" aria-label="Case sections">
      <span className="case-page-navigation-label">On this case</span>
      {sections.map(({ id, label, description, icon: Icon }) => (
        <button key={id} type="button" onClick={() => jump(id)}>
          <Icon size={18} aria-hidden="true" />
          <span>
            <strong>{label}</strong>
            <small>{description}</small>
          </span>
        </button>
      ))}
    </nav>
  );
}
