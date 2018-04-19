package net.jadoth.util.chars;

import static net.jadoth.math.JadothMath.notNegative;

import java.util.function.Function;
import java.util.function.Predicate;

import net.jadoth.Jadoth;
import net.jadoth.collections.ConstList;
import net.jadoth.collections.EqConstHashEnum;
import net.jadoth.collections.EqHashTable;
import net.jadoth.collections.types.XGettingCollection;
import net.jadoth.collections.types.XGettingEnum;
import net.jadoth.collections.types.XGettingList;
import net.jadoth.collections.types.XGettingSequence;
import net.jadoth.collections.types.XGettingTable;
import net.jadoth.collections.types.XImmutableEnum;
import net.jadoth.collections.types.XImmutableList;
import net.jadoth.csv.CSV;
import net.jadoth.csv.CsvConfiguration;
import net.jadoth.csv.CsvContent;
import net.jadoth.memory.Memory;
import net.jadoth.util.branching.ThrowBreak;

public interface StringTable
{

	/**
	 * An arbitrary name identifying this table instance, potentially <code>null</code>.
	 *
	 * @return this table's name.
	 */
	public String name();

	public XGettingEnum<String> columnNames();

	public XGettingList<String> columnTypes();

	public XGettingList<String[]> rows();
	
	public XGettingTable<String, String> toKeyValueTable(
		Function<String[], String> keyMapper  ,
		Function<String[], String> valueMapper
	);



	public interface Creator
	{
		public StringTable createStringTable(
			String                   name       ,
			XGettingSequence<String> columnNames,
			XGettingList<String>     columnTypes,
			XGettingList<String[]>   rows
		);
	}



	public final class Static
	{
		public static StringTable parse(final String rawData)
		{
			return parse(rawData, null);
		}
		
		public static StringTable parse(final _charArrayRange rawData)
		{
			return parse(rawData, null);
		}
		
		public static StringTable parse(final String rawData, final CsvConfiguration csvConfiguration)
		{
			/*
			 * can't copy around data all the time just because the JDK guys don't know how to write proper APIs
			 * (e.g. give String an iterate(_charConsumer) method so that logic could be written reusable)
			 * Or even better: make immutable arrays or optionally read-only accessible. But nooo...
			 */
			return parse(_charArrayRange.New(Memory.accessChars(rawData)), csvConfiguration);
		}
		
		public static StringTable parse(final _charArrayRange rawData, final CsvConfiguration csvConfiguration)
		{
			final CsvContentBuilderCharArray parser = CsvContentBuilderCharArray.New(
				ensureCsvConfiguration(csvConfiguration)
			);
			
			final CsvContent  content = parser.build(null, rawData);
			final StringTable data    = content.segments().first().value();

			return data;
		}
		
		// float because float to int conversion is automatically capped at max int.
		public static final int estimatedCharCountPerRow()
		{
			return 100;
		}
		
		public static final int calculateEstimatedCharCount(final long rowCount)
		{
			final long estimate = rowCount * estimatedCharCountPerRow();
			
			return estimate >= Integer.MAX_VALUE
				? Integer.MAX_VALUE
				: (int)estimate
			;
		}

		public static final String assembleString(final StringTable st)
		{
			return assembleString(VarString.New(calculateEstimatedCharCount(st.rows().size())), st).toString();
		}

		public static final VarString assembleString(final VarString vs, final StringTable st)
		{
			return assembleString(vs, st, null);
		}
		
		private static void assemble(final VarString vs, final char separator, final String[] elements)
		{
			if(elements.length == 0)
			{
				return;
			}
			
			for(final String s : elements)
			{
				vs.add(s).add(separator);
			}
			vs.deleteLast();
		}
		
		private static void assemble(final VarString vs, final char separator, final XGettingCollection<String> elements)
		{
			if(elements.isEmpty())
			{
				return;
			}
			
			for(final String s : elements)
			{
				vs.add(s).add(separator);
			}
			vs.deleteLast();
		}
		
		// (08.05.2017 TM)NOTE: centralized method to guarantee parser and assembler behave consistently
		private static CsvConfiguration ensureCsvConfiguration(final CsvConfiguration csvConfiguration)
		{
			return csvConfiguration == null
				? CSV.configurationDefault()
				: csvConfiguration
			;
		}
		
		public static final VarString assembleString(
			final VarString        vs              ,
			final StringTable      st              ,
			final CsvConfiguration csvConfiguration
		)
		{
			if(st.columnNames().isEmpty())
			{
				// column names are mandatory. So no columns means no data, even if there should be rows present.
				return vs;
				
				// (08.05.2017 TM)NOTE: can't just return a random string because it is not recognized by the parser.
//				return vs.add("[empty table]");
			}
			
			final CsvConfiguration effConfig       = ensureCsvConfiguration(csvConfiguration);
			final char             valueSeparator  = effConfig.valueSeparator();
			final char             recordSeparator = effConfig.recordSeparator();

			// assemble column names
			assemble(vs, valueSeparator, st.columnNames());

			// assemble column types if present
			if(!st.columnTypes().isEmpty())
			{
				vs.add(recordSeparator).add('(');
				assemble(vs, valueSeparator, st.columnTypes());
				vs.add(')');
			}

			// assemble data rows if present
			if(!st.rows().isEmpty())
			{
				for(final String[] row : st.rows())
				{
					assemble(vs.add(recordSeparator), valueSeparator, row);
				}
			}

			return vs;
		}


		private Static()
		{
			// static only
			throw new UnsupportedOperationException();
		}
	}



	public final class Implementation implements StringTable
	{
		public static final class Creator implements StringTable.Creator
		{

			@Override
			public StringTable createStringTable(
				final String                   name       ,
				final XGettingSequence<String> columnNames,
				final XGettingList<String>     columnTypes,
				final XGettingList<String[]>   rows
			)
			{
				return new StringTable.Implementation(name, columnNames, columnTypes, rows);
			}

		}


		///////////////////////////////////////////////////////////////////////////
		// constants        //
		/////////////////////

		private static void validateColumnCount(final int columnCount, final XGettingList<String[]> rows)
		{
			final long columnCountMismatchIndex = rows.scan(new ColumnCountValidator(columnCount));
			if(columnCountMismatchIndex >= 0)
			{
				// (01.07.2013)EXCP: proper exception
				throw new IllegalArgumentException(
					"Invalid column count in row " + columnCountMismatchIndex
					+ " (" + columnCount + " required, " + rows.at(columnCountMismatchIndex).length + " available)"
				);
			}
		}



		///////////////////////////////////////////////////////////////////////////
		// instance fields //
		////////////////////

		private final String                  name   ;
		private final EqConstHashEnum<String> columns;
		private final ConstList<String>       types  ;
		private final ConstList<String[]>     rows   ;



		///////////////////////////////////////////////////////////////////////////
		// constructors //
		/////////////////

		public Implementation(
			final XGettingSequence<String> columns    ,
			final XGettingList<String>     columnTypes,
			final XGettingList<String[]>   rows
		)
		{
			this(null, columns, columnTypes, rows);
		}

		public Implementation(
			final String                   name       ,
			final XGettingSequence<String> columns    ,
			final XGettingList<String>     columnTypes,
			final XGettingList<String[]>   rows
		)
		{
			super();
			this.name    = name                 ; // may be null
			this.columns = EqConstHashEnum.New(columns);
			validateColumnCount(Jadoth.to_int(this.columns.size()), rows);
			this.types   = ConstList.New(columnTypes);
			this.rows    = ConstList.New(rows);
		}



		///////////////////////////////////////////////////////////////////////////
		// override methods //
		/////////////////////

		@Override
		public final String name()
		{
			return this.name;
		}

		@Override
		public final XImmutableEnum<String> columnNames()
		{
			return this.columns;
		}

		@Override
		public final XGettingList<String> columnTypes()
		{
			return this.types;
		}

		@Override
		public final XImmutableList<String[]> rows()
		{
			return this.rows;
		}
		
		@Override
		public final XGettingTable<String, String> toKeyValueTable(
			final Function<String[], String> keyMapper  ,
			final Function<String[], String> valueMapper
		)
		{
			final EqHashTable<String, String> table = EqHashTable.New();
			
			for(final String[] row : this.rows)
			{
				table.add(keyMapper.apply(row), valueMapper.apply(row));
			}

			return table;
		}


		static final class ColumnCountValidator implements Predicate<String[]>
		{
			///////////////////////////////////////////////////////////////////////////
			// instance fields  //
			/////////////////////

			private final int columnCount;


			///////////////////////////////////////////////////////////////////////////
			// constructors     //
			/////////////////////

			ColumnCountValidator(final int columnCount)
			{
				super();
				this.columnCount = notNegative(columnCount);
			}



			///////////////////////////////////////////////////////////////////////////
			// override methods //
			/////////////////////

			@Override
			public final boolean test(final String[] row) throws ThrowBreak
			{
				return row.length != this.columnCount;
			}

		}

	}

}
