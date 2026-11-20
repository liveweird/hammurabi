import { type ComponentProps } from "react";
import { Typography } from "@mantine/core";
import ReactMarkdown, { type Components, type ExtraProps } from "react-markdown";
import remarkGfm from "remark-gfm";
import remarkGemoji from "remark-gemoji";
import classes from "./MarkdownView.module.css";

// Read-only renderer for the markdown strings the app carries. Single home for the Mantine
// Typography wrapper + the GFM/gemoji plugin set (Lettuce's, ported).
//
// Two variants:
// - "document" (default): long-form content under its own page chrome (the changelog bodies);
//   plain react-markdown output, unchanged.
// - "message": chat message bodies, where dozens of user-written bodies share one page, so
//   a body may not shape the page: headings become bold paragraphs (50 messages cannot flood
//   the heading outline), an image becomes a link (the CSP is `img-src 'self' data:`, a remote
//   image would be blocked anyway), a link opens in a new tab with `rel="noopener noreferrer"`.
//   Neither variant renders raw HTML (react-markdown's default; there is no rehype-raw).
export type MarkdownVariant = "document" | "message";

type WithNode<T extends keyof React.JSX.IntrinsicElements> = ComponentProps<T> & ExtraProps;

const boldParagraph = ({ node: _node, ...props }: WithNode<"h1">) => (
  <p {...(props as ComponentProps<"p">)} className={classes.heading} />
);

const messageComponents: Components = {
  h1: boldParagraph,
  h2: boldParagraph,
  h3: boldParagraph,
  h4: boldParagraph,
  h5: boldParagraph,
  h6: boldParagraph,
  a: ({ node: _node, ...props }: WithNode<"a">) => <a {...props} target="_blank" rel="noopener noreferrer" />,
  // react-markdown's default urlTransform already blanks an unsafe `src` (javascript:, data: …);
  // with nothing to link to, the alt text stays as plain text.
  img: ({ node: _node, src, alt }: WithNode<"img">) =>
    typeof src === "string" && src !== "" ? (
      <a href={src} target="_blank" rel="noopener noreferrer">
        {alt || src}
      </a>
    ) : (
      <span>{alt}</span>
    ),
};

export default function MarkdownView({
  children,
  variant = "document",
}: {
  children: string | null | undefined;
  variant?: MarkdownVariant;
}) {
  const message = variant === "message";
  return (
    <Typography className={message ? classes.message : undefined}>
      <ReactMarkdown remarkPlugins={[remarkGfm, remarkGemoji]} components={message ? messageComponents : undefined}>
        {children}
      </ReactMarkdown>
    </Typography>
  );
}
