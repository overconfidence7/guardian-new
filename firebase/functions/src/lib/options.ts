import { setGlobalOptions } from "firebase-functions/v2";

// Imported first by index.ts so these apply to every function definition.
setGlobalOptions({ region: "us-central1", maxInstances: 10 });
