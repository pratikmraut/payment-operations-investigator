import { ChevronRight } from "lucide-react";

const steps = [
  {
    id: "find",
    label: "Find payment",
    description: "Search or import a transaction",
  },
  {
    id: "case",
    label: "Case",
    description: "Save the payment and your reason",
  },
  {
    id: "evidence",
    label: "Evidence",
    description: "Collect and check source records",
  },
  {
    id: "investigation",
    label: "Investigation",
    description: "Ask, review and report",
  },
] as const;

export function PaymentWorkflow({
  current,
}: {
  current: (typeof steps)[number]["id"];
}) {
  return (
    <ol
      className="payment-workflow"
      aria-label="Payment investigation workflow"
    >
      {steps.map((step, index) => (
        <li
          key={step.id}
          className={step.id === current ? "current" : ""}
          aria-current={step.id === current ? "step" : undefined}
        >
          <span>{index + 1}</span>
          <div className="workflow-copy">
            <strong>{step.label}</strong>
            <small>{step.description}</small>
          </div>
          {index < steps.length - 1 && (
            <ChevronRight size={14} aria-hidden="true" />
          )}
        </li>
      ))}
    </ol>
  );
}
