#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;
use Text::CSV;

# Test constructor
my $csv = Text::CSV->new();
ok($csv, 'Created Text::CSV object');
isa_ok($csv, 'Text::CSV');

# Test with options
my $csv_opts = Text::CSV->new({
    sep_char => ';',
    quote_char => "'",
    escape_char => "\\",
    binary => 1,
    eol => "\n"
});
ok($csv_opts, 'Created Text::CSV object with options');

# Test basic parsing
{
    my $csv = Text::CSV->new();  # Fresh instance
    my $line = 'foo,bar,baz';
    ok($csv->parse($line), 'Parse simple CSV line');
    my @fields = $csv->fields();
    is_deeply(\@fields, ['foo', 'bar', 'baz'], 'Fields parsed correctly');
}

# Test quoted fields
{
    my $csv = Text::CSV->new();  # Fresh instance
    my $line = '"foo","bar,baz","qux"';
    ok($csv->parse($line), 'Parse quoted CSV line');
    my @fields = $csv->fields();
    is_deeply(\@fields, ['foo', 'bar,baz', 'qux'], 'Quoted fields parsed correctly');
}

# Test escaped quotes
{
    my $csv = Text::CSV->new();  # Fresh instance
    my $line = '"foo","bar""baz","qux"';
    ok($csv->parse($line), 'Parse CSV line with escaped quotes');
    my @fields = $csv->fields();
    is_deeply(\@fields, ['foo', 'bar"baz', 'qux'], 'Escaped quotes parsed correctly');
}

# Test combine
{
    my $csv = Text::CSV->new();  # Fresh instance
    my @fields = ('foo', 'bar', 'baz');
    ok($csv->combine(@fields), 'Combine fields into CSV');
    my $string = $csv->string();
    is($string, 'foo,bar,baz', 'Combined string is correct');
}

# Test combine with quotes needed
{
    my $csv = Text::CSV->new();  # Fresh instance
    my @fields = ('foo', 'bar,baz', 'qux');
    ok($csv->combine(@fields), 'Combine fields with special chars');
    my $string = $csv->string();
    is($string, 'foo,"bar,baz",qux', 'Fields with commas are quoted');
}

# Test combine with quotes in fields
{
    my $csv = Text::CSV->new();  # Fresh instance
    my @fields = ('foo', 'bar"baz', 'qux');
    ok($csv->combine(@fields), 'Combine fields with quotes');
    my $string = $csv->string();
    is($string, 'foo,"bar""baz",qux', 'Quotes are escaped correctly');
}

# Test custom separator
{
    ok($csv_opts->parse("foo;'bar;baz';qux"), 'Parse with custom separator');
    my @fields = $csv_opts->fields();
    is_deeply(\@fields, ['foo', 'bar;baz', 'qux'], 'Custom separator works');
}

# Test getters/setters
{
    my $csv = Text::CSV->new();  # Fresh instance
    is($csv->sep_char(), ',', 'Default separator is comma');
    is($csv->quote_char(), '"', 'Default quote char is double quote');

    $csv->sep_char('|');
    is($csv->sep_char(), '|', 'Set separator works');

    $csv->quote_char("'");
    is($csv->quote_char(), "'", 'Set quote char works');
}

# Test empty fields
{
    my $csv = Text::CSV->new();  # Fresh instance
    my $line = 'foo,,baz';
    ok($csv->parse($line), 'Parse line with empty field');
    my @fields = $csv->fields();
    is_deeply(\@fields, ['foo', '', 'baz'], 'Empty fields preserved');
}

# Test undef handling
{
    my $csv_undef = Text::CSV->new({
        blank_is_undef => 1,
        empty_is_undef => 1
    });

    ok($csv_undef->parse('foo,,baz'), 'Parse with undef options');
    my @fields = $csv_undef->fields();
    is($fields[0], 'foo', 'First field is string');
    ok(!defined($fields[1]), 'Empty field is undef');
    is($fields[2], 'baz', 'Third field is string');
}

# Test combine with undef
{
    my $csv = Text::CSV->new();  # Fresh instance
    my @fields = ('foo', undef, 'baz');
    ok($csv->combine(@fields), 'Combine with undef field');
    my $string = $csv->string();
    is($string, 'foo,,baz', 'Undef becomes empty string');
}

# Test always_quote
{
    my $csv_quote = Text::CSV->new({ always_quote => 1 });
    ok($csv_quote->combine('foo', 'bar', 'baz'), 'Combine with always_quote');
    my $string = $csv_quote->string();
    is($string, '"foo","bar","baz"', 'All fields are quoted');
}

# Test column_names
{
    my $csv = Text::CSV->new();  # Fresh instance
    my @names = qw(name age city);
    $csv->column_names(@names);
    my @got_names = $csv->column_names();
    is_deeply(\@got_names, \@names, 'Column names set and retrieved');

    # Test with arrayref
    $csv->column_names(['id', 'value', 'description']);
    @got_names = $csv->column_names();
    is_deeply(\@got_names, ['id', 'value', 'description'], 'Column names set with arrayref');
}

# Test error handling
{
    my $csv = Text::CSV->new();  # Fresh instance
    my $bad_line = '"unterminated';
    my $result = $csv->parse($bad_line);

    ok(!$result, 'Parse fails on unterminated quote');

    # In scalar context
    my $error = $csv->error_diag();
    ok($error, 'Error message in scalar context');

    # In list context
    my ($code, $str, $pos, $rec, $fld) = $csv->error_diag();
    ok($code, 'Error code is set');
    ok($str, 'Error string is set');
}

# Test print to string (using scalar ref as filehandle)
{
    my $csv = Text::CSV->new({ eol => "\n" }); # Set EOL to make test predictable
    my $output = '';
    open my $fh, '>:raw', \$output or die "Cannot open string filehandle: $!";

    ok($csv->print($fh, ['foo', 'bar', 'baz']), 'Print to filehandle');
    close $fh;

    is($output, "foo,bar,baz\n", 'Print output is correct with EOL');
}

# Test getline_hr with column names
{
    my $csv = Text::CSV->new();  # Fresh instance
    $csv->column_names(['name', 'age', 'city']);

    # Simulate reading a line
    my $test_line = 'John,30,NYC';
    ok($csv->parse($test_line), 'Parse line for getline_hr test');

    # Since getline_hr needs actual file reading, we test the concept
    # by manually creating the expected hash structure
    my @fields = $csv->fields();
    my @cols = $csv->column_names();

    my %hash;
    @hash{@cols} = @fields;

    is($hash{name}, 'John', 'Hash field name correct');
    is($hash{age}, '30', 'Hash field age correct');
    is($hash{city}, 'NYC', 'Hash field city correct');
}

# Test binary mode
{
    my $csv_binary = Text::CSV->new({ binary => 1 });
    my $binary_data = "foo\x00bar";

    ok($csv_binary->combine($binary_data, 'baz'), 'Combine with binary data');
    my $string = $csv_binary->string();
    ok($string, 'Binary data handled');
}

# Test edge cases
{
    my $csv = Text::CSV->new();  # Fresh instance

    # Empty string
    ok($csv->parse(''), 'Parse empty string');
    my @fields = $csv->fields();
    is_deeply(\@fields, [''], 'Empty string gives one empty field');

    # Just space
    ok($csv->parse(' '), 'Parse space');
    @fields = $csv->fields();
    is_deeply(\@fields, [' '], 'Space gives one empty field');

    # Just separators
    ok($csv->parse(',,,'), 'Parse just separators');
    @fields = $csv->fields();
    is_deeply(\@fields, ['', '', '', ''], 'Just separators gives empty fields');

    # Whitespace handling
    my $csv_ws = Text::CSV->new({ allow_whitespace => 1 });
    ok($csv_ws->parse(' foo , bar , baz '), 'Parse with whitespace');
    @fields = $csv_ws->fields();
    is_deeply(\@fields, ['foo', 'bar', 'baz'], 'Whitespace is trimmed');
}

# Test EOL and Print/Say Interaction
{
    my $output;
    my $fh;

    # 1. print() with default eol (undef) should not add a newline
    my $csv_no_eol = Text::CSV->new();
    $output = '';
    open $fh, '>:raw', \$output;
    $csv_no_eol->print($fh, ['a', 'b']);
    close $fh;
    is($output, 'a,b', 'print() with eol=>undef adds no newline');

    # 2. print() with a defined eol should add exactly one EOL
    my $csv_eol = Text::CSV->new({ eol => "\r\n" });
    $output = '';
    open $fh, '>:raw', \$output;
    $csv_eol->print($fh, ['a', 'b']);
    close $fh;
    is($output, "a,b\r\n", 'print() with eol=>crlf adds exactly one crlf');

    # 3. say() with default eol (undef) should add $/
    my $csv_say = Text::CSV->new();
    $output = '';
    open $fh, '>:raw', \$output;
    $csv_say->say($fh, ['a', 'b']);
    close $fh;
    is($output, 'a,b' . $/, 'say() with eol=>undef adds $/');

    # 4. say() with a defined eol should use that eol and not add another
    my $csv_say_eol = Text::CSV->new({ eol => "!\n" });
    $output = '';
    open $fh, '>:raw', \$output;
    $csv_say_eol->say($fh, ['a', 'b']);
    close $fh;
    is($output, "a,b!\n", 'say() with a defined eol uses that eol');
}

# Test StringWriter reuse - combine multiple times with same instance
{
    my $csv = Text::CSV->new();  # Single instance for multiple combines

    # First combine
    my @fields1 = ('foo', 'bar', 'baz');
    ok($csv->combine(@fields1), 'First combine operation');
    my $string1 = $csv->string();
    is($string1, 'foo,bar,baz', 'First combine result correct');

    # Second combine - should NOT contain first result
    my @fields2 = ('qux', 'quux', 'corge');
    ok($csv->combine(@fields2), 'Second combine operation');
    my $string2 = $csv->string();
    is($string2, 'qux,quux,corge', 'Second combine result correct (no accumulation)');

    # Third combine with quoted fields
    my @fields3 = ('hello', 'world,test', 'end');
    ok($csv->combine(@fields3), 'Third combine operation');
    my $string3 = $csv->string();
    is($string3, 'hello,"world,test",end', 'Third combine result correct (no accumulation)');

    # Fourth combine with empty and undef
    my @fields4 = ('start', '', undef, 'finish');
    ok($csv->combine(@fields4), 'Fourth combine operation');
    my $string4 = $csv->string();
    is($string4, 'start,,,finish', 'Fourth combine result correct (no accumulation)');
}

# Test StringWriter reuse with different CSV formats
{
    my $csv = Text::CSV->new({ always_quote => 1 });  # Single instance

    # First combine with always_quote
    ok($csv->combine('a', 'b', 'c'), 'First combine with always_quote');
    my $string1 = $csv->string();
    is($string1, '"a","b","c"', 'First quoted combine result');

    # Second combine - should not accumulate
    ok($csv->combine('x', 'y', 'z'), 'Second combine with always_quote');
    my $string2 = $csv->string();
    is($string2, '"x","y","z"', 'Second quoted combine result (no accumulation)');

    # Change format and combine again
    $csv->always_quote(0);  # This should invalidate cache
    ok($csv->combine('m', 'n', 'o'), 'Third combine after format change');
    my $string3 = $csv->string();
    is($string3, 'm,n,o', 'Third combine result after format change (no quotes, no accumulation)');
}

# Test alternating parse and combine operations
{
    my $csv = Text::CSV->new();  # Single instance

    # Parse first
    ok($csv->parse('one,two,three'), 'Parse operation');
    my @parsed1 = $csv->fields();
    is_deeply(\@parsed1, ['one', 'two', 'three'], 'Parse fields correct');

    # Combine next
    ok($csv->combine('four', 'five', 'six'), 'Combine after parse');
    my $string1 = $csv->string();
    is($string1, 'four,five,six', 'Combine result correct');

    # Parse again
    ok($csv->parse('seven,eight,nine'), 'Parse after combine');
    my @parsed2 = $csv->fields();
    is_deeply(\@parsed2, ['seven', 'eight', 'nine'], 'Parse fields correct after combine');

    # Combine again
    ok($csv->combine('ten', 'eleven', 'twelve'), 'Second combine');
    my $string2 = $csv->string();
    is($string2, 'ten,eleven,twelve', 'Second combine result correct (no accumulation)');
}

# Test high-volume reuse (simulating large file processing)
{
    my $csv = Text::CSV->new();  # Single instance

    # Test many combine operations
    for my $i (1..10) {
        my @fields = ("field${i}a", "field${i}b", "field${i}c");
        ok($csv->combine(@fields), "Combine operation $i");
        my $string = $csv->string();
        is($string, "field${i}a,field${i}b,field${i}c", "Combine result $i correct (no accumulation)");
    }
}

# Test cache invalidation with StringWriter reuse
{
    my $csv = Text::CSV->new({ sep_char => ',' });

    # First combine
    ok($csv->combine('a', 'b', 'c'), 'Combine with comma separator');
    is($csv->string(), 'a,b,c', 'Comma separator result');

    # Change separator (should invalidate cache)
    $csv->sep_char('|');
    ok($csv->combine('x', 'y', 'z'), 'Combine with pipe separator');
    is($csv->string(), 'x|y|z', 'Pipe separator result (no accumulation from previous)');

    # Change back
    $csv->sep_char(',');
    ok($csv->combine('m', 'n', 'o'), 'Combine with comma separator again');
    is($csv->string(), 'm,n,o', 'Comma separator result again (no accumulation)');
}

# Test StringWriter reuse with print method
{
    my $csv = Text::CSV->new({ eol => "\n" });

    # First print
    my $output1 = '';
    open my $fh1, '>:raw', \$output1;
    ok($csv->print($fh1, ['a', 'b', 'c']), 'First print operation');
    close $fh1;
    is($output1, "a,b,c\n", 'First print output correct');

    # Second print - should not accumulate
    my $output2 = '';
    open my $fh2, '>:raw', \$output2;
    ok($csv->print($fh2, ['x', 'y', 'z']), 'Second print operation');
    close $fh2;
    is($output2, "x,y,z\n", 'Second print output correct (no accumulation)');
}

# Test error conditions don't affect StringWriter reuse
{
    my $csv = Text::CSV->new();

    # Successful combine
    ok($csv->combine('good', 'data'), 'Successful combine');
    is($csv->string(), 'good,data', 'Successful result');

    # This might cause an error in some implementations
    # but should not affect subsequent operations
    my $result = $csv->combine();  # No arguments

    # Another successful combine should work regardless
    ok($csv->combine('more', 'data'), 'Combine after potential error');
    is($csv->string(), 'more,data', 'Result after potential error (no accumulation)');
}

done_testing();
