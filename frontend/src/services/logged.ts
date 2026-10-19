export async function logged<T>(label: string, read: () => Promise<T>): Promise<T> {
  try {
    return await read();
  } catch (err) {
    console.error(label, err);
    throw err;
  }
}
