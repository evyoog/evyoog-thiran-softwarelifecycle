package com.vyoog.importqueue;

/**
 * VYB-0631/0666: chosen at upload; a STANDARD_SPEC is validated more strictly than a
 * FREEFORM one.
 *
 * <p>{@code PRD_TEMPLATE} is the one kind that does not go through a {@link
 * DocumentParser} at all — the template states every field in a labelled column, so it
 * is read deterministically rather than interpreted. See {@code prd.PrdTemplateParser}.
 */
public enum UploadKind { FREEFORM, STANDARD_SPEC, REQIF, EXCEL, WORD, PRD_TEMPLATE }
