import React, { useState } from "react";
import Header from "./components/Header";
import HeroSection from "./components/HeroSection";
import FeatureGrid from "./components/FeatureGrid";
import ArchitecturePanel from "./components/ArchitecturePanel";
import PhaseStatus from "./components/PhaseStatus";
import Footer from "./components/Footer";

export default function App() {
  const [activeTab, setActiveTab] = useState("overview");

  return (
    <div style={{ minHeight: "100vh", display: "flex", flexDirection: "column" }}>
      <Header activeTab={activeTab} setActiveTab={setActiveTab} />
      <main style={{ flex: 1 }}>
        {activeTab === "overview" && (
          <>
            <HeroSection />
            <FeatureGrid />
          </>
        )}
        {activeTab === "architecture" && <ArchitecturePanel />}
        {activeTab === "status" && <PhaseStatus />}
      </main>
      <Footer />
    </div>
  );
}
