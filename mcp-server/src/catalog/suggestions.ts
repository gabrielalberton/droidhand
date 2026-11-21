import { z } from "zod";
import { SUGGESTION_CATEGORIES } from "../core/suggestions.js";
import type { ToolArea } from "./types.js";

export const suggestions: ToolArea = {
  id: "suggestions",
  title: "Feature suggestions",
  summary: "Agent-written improvement ideas; off by default, enabled on the device.",
  tools: [
    { name: "suggest_feature", tier: "standard", confirm: false, local: true,
      description:
        "Log an idea for a future DroidHand feature/capability/harness/optimization/routine that would speed up future work. " +
        "No-op unless the operator enabled suggestion logging in the app (OFF by default).",
      input: {
        title: z.string(),
        detail: z.string(),
        category: z.enum(SUGGESTION_CATEGORIES).optional(),
        priority: z.number().min(1).max(5).optional(),
        context: z.string().optional(),
      } },
    { name: "list_suggestions", tier: "standard", confirm: false, local: true,
      description: "List logged feature suggestions.", input: {} },
    { name: "export_suggestions", tier: "standard", confirm: false, local: true,
      description: "Render the suggestions as a Markdown digest for review.", input: {} },
  ],
};
