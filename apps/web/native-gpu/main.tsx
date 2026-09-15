import React from "react";
import ReactDOM from "react-dom/client";
import App from "../src/App";
import "../src/styles.css";
import "../src/ui-refresh.css";
import "../src/discovery-refresh.css";
import "../src/workbench-refresh.css";
import "../src/library-refresh.css";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
