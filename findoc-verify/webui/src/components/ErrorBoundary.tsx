import { Component, type ReactNode } from "react";

type Props = {
  children: ReactNode;
  fallback?: ReactNode;
};

type State = { hasError: boolean; error: any };

export class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false, error: null };

  static getDerivedStateFromError(error: any): State {
    return { hasError: true, error };
  }

  componentDidCatch(error: any, info: any) {
    console.error("ErrorBoundary caught:", error, info);
  }

  render() {
    if (this.state.hasError) {
      return (
        this.props.fallback ?? (
          <div className="rounded border border-amber-300 bg-amber-50 px-3 py-2 text-xs text-amber-900">
            <div className="font-semibold mb-1">Render error in this section</div>
            <div className="font-mono">
              {String(this.state.error?.message ?? this.state.error ?? "unknown")}
            </div>
            <div className="mt-1 text-amber-700">
              The pipeline returned data this view didn't expect. Other sections of the page should still work.
            </div>
          </div>
        )
      );
    }
    return this.props.children;
  }
}
