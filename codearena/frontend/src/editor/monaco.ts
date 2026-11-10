// Bundle Monaco locally (no CDN), so the editor works offline and under a strict Content-Security-Policy.
// Paths use the package's exports map (monaco-editor 0.56+ reorganized its ESM entry points).
import { loader } from "@monaco-editor/react";
import * as monaco from "monaco-editor";
import EditorWorker from "monaco-editor/editor/editor.worker?worker";
import TsWorker from "monaco-editor/languages/features/typescript/ts.worker?worker";

self.MonacoEnvironment = {
  getWorker(_id: string, label: string) {
    if (label === "typescript" || label === "javascript") return new TsWorker();
    return new EditorWorker();
  },
};

monaco.editor.defineTheme("codearena", {
  base: "vs-dark",
  inherit: true,
  rules: [
    { token: "keyword", foreground: "FFB627" },
    { token: "comment", foreground: "7C82B4", fontStyle: "italic" },
    { token: "string", foreground: "8EE6C0" },
    { token: "number", foreground: "F7A1C4" },
  ],
  colors: {
    "editor.background": "#1A1D3A",
    "editor.lineHighlightBackground": "#232750",
    "editorLineNumber.foreground": "#5A6098",
    "editorLineNumber.activeForeground": "#EDEFFF",
    "editorCursor.foreground": "#FFB627",
    "editor.selectionBackground": "#3B4380",
    "editorIndentGuide.background1": "#2C3160",
  },
});

loader.config({ monaco });

export { monaco };