import { describe, expect, test } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { isBoolean, isOneOf, isOneOfOrNull, isString, readStoredJson, useStoredState, writeStoredJson } from "./useStoredState";

const KEY = "testView.filter.name";
const STORAGE_KEY = `hammurabi.viewSettings.${KEY}`;

describe("useStoredState", () => {
  test("persists a set value under the hammurabi.viewSettings key and restores it on mount", () => {
    const { result } = renderHook(() => useStoredState(KEY, "", isString));
    expect(result.current[0]).toBe("");

    act(() => result.current[1]("web-app"));
    expect(result.current[0]).toBe("web-app");
    expect(localStorage.getItem(STORAGE_KEY)).toBe(JSON.stringify("web-app"));

    // A fresh mount (a reload / navigation back) restores the stored value.
    const { result: remounted } = renderHook(() => useStoredState(KEY, "", isString));
    expect(remounted.current[0]).toBe("web-app");
  });

  test("corrupt stored JSON falls back to the default", () => {
    localStorage.setItem(STORAGE_KEY, "{not json");
    const { result } = renderHook(() => useStoredState(KEY, "fallback", isString));
    expect(result.current[0]).toBe("fallback");
  });

  test("a stored value failing the type guard falls back to the default", () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(42));
    const { result } = renderHook(() => useStoredState(KEY, "fallback", isString));
    expect(result.current[0]).toBe("fallback");
  });

  test("an absent key yields the default", () => {
    const { result } = renderHook(() => useStoredState(KEY, "initial", isString));
    expect(result.current[0]).toBe("initial");
  });
});

describe("readStoredJson / writeStoredJson", () => {
  test("round-trips structured values and reports absence as undefined", () => {
    expect(readStoredJson("nowhere")).toBeUndefined();
    writeStoredJson("obj", { a: 1 });
    expect(readStoredJson("obj")).toEqual({ a: 1 });
  });
});

describe("validators", () => {
  test("isString accepts only strings", () => {
    expect(isString("x")).toBe(true);
    expect(isString("")).toBe(true);
    expect(isString(42)).toBe(false);
    expect(isString(null)).toBe(false);
    expect(isString(undefined)).toBe(false);
  });

  test("isBoolean accepts only booleans", () => {
    expect(isBoolean(true)).toBe(true);
    expect(isBoolean(false)).toBe(true);
    expect(isBoolean("true")).toBe(false);
    expect(isBoolean(0)).toBe(false);
  });
});

describe("closed-list validators", () => {
  test("isOneOf accepts only listed values; isOneOfOrNull also accepts null", () => {
    const isSize = isOneOf(["s", "m"] as const);
    expect(isSize("s")).toBe(true);
    expect(isSize("xl")).toBe(false);
    expect(isSize(null)).toBe(false);
    const isSizeOrNull = isOneOfOrNull(["s", "m"] as const);
    expect(isSizeOrNull(null)).toBe(true);
    expect(isSizeOrNull("m")).toBe(true);
    expect(isSizeOrNull("xl")).toBe(false);
  });
});
