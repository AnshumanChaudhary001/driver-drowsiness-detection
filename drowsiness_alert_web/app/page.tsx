"use client";

import { useState, useEffect } from "react";
import { db } from "../lib/firebase";
import {
  ref,
  onValue,
  query,
  orderByChild,
  limitToLast,
} from "firebase/database";

type Alert = {
  id: string;
  driverId: string;
  timestamp: number;
};

export default function SupervisorDashboard() {
  const [alerts, setAlerts] = useState<Alert[]>([]);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    const alertsRef = query(
      ref(db, "alerts"),
      orderByChild("timestamp"),
      limitToLast(20)
    );

    const unsubscribe = onValue(alertsRef, (snapshot) => {
      const data = snapshot.val();
      if (data) {
        const alertsList: Alert[] = Object.keys(data)
          .map((key) => ({
            id: key,
            ...data[key],
          }))
          .sort((a, b) => b.timestamp - a.timestamp);

        setAlerts(alertsList);
      } else {
        setAlerts([]);
      }
      setIsLoading(false);
    });

    return () => {
      unsubscribe();
    };
  }, []);

  return (
    <main className="flex min-h-screen flex-col items-center p-12 bg-gray-900 text-white">
      <div className="z-10 w-full max-w-5xl">
        <h1 className="text-4xl font-bold mb-8 text-center text-orange-400">
          🚨 Driver Drowsiness Alerts
        </h1>

        <div className="w-full bg-gray-800 rounded-lg shadow-lg p-6">
          <h2 className="text-2xl mb-4 border-b border-gray-600 pb-2">
            Live Feed
          </h2>

          {isLoading && <p>Listening for alerts...</p>}

          {!isLoading && alerts.length === 0 && (
            <p>No alerts detected yet. All drivers are safe.</p>
          )}

          {alerts.length > 0 && (
            <ul className="space-y-4">
              {alerts.map((alert) => (
                <li
                  key={alert.id}
                  className="p-4 bg-red-900/50 border border-red-700 rounded-md animate-pulse"
                >
                  <p className="font-bold text-lg">
                    ALERT:{" "}
                    <span className="text-yellow-300">{alert.driverId}</span> is
                    Drowsy
                  </p>
                  <p className="text-sm text-gray-300">
                    {new Date(alert.timestamp).toLocaleString()}
                  </p>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </main>
  );
}
