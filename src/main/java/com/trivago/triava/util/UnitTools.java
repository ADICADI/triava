package com.trivago.triava.util;

import java.text.DecimalFormat;

/**
 * The UnitTools supply methods to convert a plain value into components, based on Units
 * @author cesken
 *
 */
public class UnitTools
{
	/**
	 * Formats the value to human readable representation, using the given Unit System. 
	 * with a maximum of two fraction digits, e.g. 13504653 is converted to "13.5M". If you need more control on the format, call
	 * {@link #formatAsUnit(long, UnitSystem, String, DecimalFormat)}.
	 * 
	 * @param number The number to format
	 * @param unitSystem The Unit system
	 * @param unitPostfix The postfix, for example "B" for byte or "W" for Watt. May be empty.
	 * 
	 * @return The formatted value
	 */
	public static String formatAsUnit(long value, UnitSystem unitSystem, String unitPostfix)
	{
		return formatAsUnit(value, unitSystem, unitPostfix, new DecimalFormat("0.##"));
	}
	
	/**
	 * Formats the value to human readable representation, using the given Unit System. 
	 * For example 13500653 is converted to "13.501MB".
	 * 
	 * @param number The number to format
	 * @param unitSystem The Unit system
	 * @param unitPostfix The postfix, for example "B" for byte or "W" for Watt. May be empty.
	 * @param format The decimal format for formatting the number
	 * 
	 * @return The formatted value
	 */
	public static String formatAsUnit(long value, UnitSystem unitSystem, String unitPostfix, DecimalFormat format)
	{
		StringBuilder formatedSize = new StringBuilder(12);

		if (value < unitSystem.kiloPrefix().value())
			formatedSize.append(value).append(unitSystem.basePrefix().symbol());
		else if (value < unitSystem.megaPrefix().value())
			formatedSize.append(formatToDigits(value, unitSystem.kiloPrefix().value(), format)).append(unitSystem.kiloPrefix().symbol());
		else if (value < unitSystem.gigaPrefix().value())
			formatedSize.append(formatToDigits(value, unitSystem.megaPrefix().value(), format)).append(unitSystem.megaPrefix().symbol());
		else
			formatedSize.append(formatToDigits(value, unitSystem.gigaPrefix().value(), format)).append(unitSystem.gigaPrefix().symbol());
		    
		return formatedSize.append(unitPostfix).toString();

	}
	
	private static String formatToDigits(long value, double base, DecimalFormat format)
	{
		double roundedValue = value/(double)base;
		return format.format(roundedValue);
	}

	/**
	 * Formats the value to human readable representation, using the given Unit System. 
	 * Zero values are omitted, e.g. 13000653 is converted to "13MB 653B".
	 * 
	 * @param number The number to format
	 * @param unitSystem The Unit system
	 * @param unitPostfix The postfix, for example "B" for byte or "W" for Watt. May be empty.
	 * 
	 * @return The formatted value
	 */
	public static String formatAsUnits(long size, UnitSystem unitSystem, String unitPostfix, String separator)
	{
		UnitComponent comp = new UnitComponent(size, unitSystem);
		int GB = comp.giga();
		int MB = comp.mega();
		int KB = comp.kilo();
		int B = comp.base();

		StringBuilder formatedSize = new StringBuilder(32);
		if (GB > 0)
			formatedSize.append(GB).append(unitSystem.gigaPrefix().symbol()).append(unitPostfix).append(separator);
		if (MB > 0)
			formatedSize.append(MB).append(unitSystem.megaPrefix().symbol()).append(unitPostfix).append(separator);
		if (KB > 0)
			formatedSize.append(KB).append(unitSystem.kiloPrefix().symbol()).append(unitPostfix).append(separator);
		formatedSize.append(B).append(unitSystem.basePrefix().symbol()).append(unitPostfix).append(separator);

		formatedSize.setLength(formatedSize.length()-separator.length()); // Strip off final separator
		return formatedSize.toString();
	}
}
