"""Inject the Mermaid diagrams from docs/merge-workflow.md into the HTML template.

The Markdown doc is the single source of truth for the diagrams.
"""
import html
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
MD = ROOT / "docs/merge-workflow.md"

TEMPLATE = ROOT / "docs/site/merge-workflow.template.html"
OUT = ROOT / "docs/site/merge-workflow.html"

THEME = (
    '%%{init: {"theme": "base", "flowchart": {"curve": "basis", "htmlLabels": true}, '
    '"themeVariables": {"fontFamily": "IBM Plex Sans, system-ui, sans-serif", "fontSize": "14px", '
    '"background": "#fbfcfb", "primaryColor": "#e3f0ee", "primaryBorderColor": "#0f6e6a", '
    '"primaryTextColor": "#17201e", "lineColor": "#56655f", "textColor": "#17201e", '
    '"clusterBkg": "#f1f5f4", "clusterBorder": "#b8cac6", "titleColor": "#0f6e6a", '
    '"edgeLabelBackground": "#fbfcfb", "tertiaryColor": "#f1f5f4"}}}%%'
)
# Decisions (rhombus nodes) get the amber treatment; data nodes the grey one.
CLASSES = (
    "    classDef decide fill:#fbf0dc,stroke:#9a6414,color:#17201e\n"
    "    classDef data fill:#eef2f1,stroke:#6b7c78,color:#17201e\n"
)


def style(diagram: str) -> str:
    decisions = re.findall(r"^\s*(\w+)\{", diagram, re.M) + re.findall(r"-->\s*(?:\|[^|]*\|\s*)?(\w+)\{", diagram)
    data = re.findall(r"(\w+)\[/", diagram)
    lines = [THEME, diagram.rstrip(), CLASSES.rstrip()]
    if decisions:
        lines.append(f"    class {','.join(dict.fromkeys(decisions))} decide")
    if data:
        lines.append(f"    class {','.join(dict.fromkeys(data))} data")
    return "\n".join(lines)


def main() -> None:
    diagrams = re.findall(r"```mermaid\n(.*?)```", MD.read_text(), re.S)
    page = TEMPLATE.read_text()
    for i, diagram in enumerate(diagrams, start=1):
        page = page.replace(f"{{{{DIAGRAM_{i}}}}}", html.escape(style(diagram), quote=False))
    if "{{DIAGRAM_" in page:
        sys.exit("template has placeholders without a matching diagram")
    OUT.write_text(page)
    print(f"{len(diagrams)} diagrams -> {OUT}")


if __name__ == "__main__":
    main()
