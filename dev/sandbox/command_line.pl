#!/usr/bin/perl
use strict;
use warnings;
use Test::More tests => 20;
use File::Temp qw(tempfile tempdir);
use File::Spec;
use File::Path qw(rmtree);

my $perl;
$perl = 'perl';
$perl = 'java -jar target/perlonjava-3.0.0.jar';

# Test -c (compile only)
{
    my ($fh, $filename) = tempfile();
    print $fh 'print "Hello, World!\n";';
    close $fh;

    my $output = `$perl -c $filename 2>&1`;
    like($output, qr/Syntax OK/i, '-c switch compiles without errors');
    unlink $filename;
}

# Test -e (execute code)
{
    my $output = `$perl -e 'print "Hello, World!\n"'`;
    is($output, "Hello, World!\n", '-e switch executes code');
}

# Test -E (execute code with version)
{
    my $output = `$perl -E 'say "Hello, World!"'`;
    is($output, "Hello, World!\n", '-E switch executes code with version');
}

# Test -p (process and print)
{
    my ($fh, $filename) = tempfile();
    print $fh "Hello\nWorld\n";
    close $fh;

    my $output = `$perl -pe '' $filename`;
    is($output, "Hello\nWorld\n", '-p switch processes and prints each line');
    unlink $filename;
}

# Test -n (process only)
{
    my ($fh, $filename) = tempfile();
    print $fh "Hello\nWorld\n";
    close $fh;

    my $output = `$perl -ne '' $filename`;
    is($output, "", '-n switch processes each line without printing');
    unlink $filename;
}

# Test -i (in-place editing)
{
    my ($fh, $filename) = tempfile();
    print $fh "Hello\nWorld\n";
    close $fh;

    `$perl -pi -e 's/World/Perl/' $filename`;
    open my $fh_in, '<', $filename;
    my $content = do { local $/; <$fh_in> };
    close $fh_in;
    is($content, "Hello\nPerl\n", '-i switch edits file in place');
    unlink $filename;
}

# Test -I (include directory)
{
    my $tempdir = tempdir(CLEANUP => 1);
    my $libdir = File::Spec->catdir($tempdir, 'lib');
    mkdir $libdir;
    my $module_file = File::Spec->catfile($libdir, 'Hello.pm');
    open my $fh, '>', $module_file;
    print $fh "package Hello; sub greet { return 'Hello, World!'; } 1;";
    close $fh;

    my $output = `$perl -I$libdir -MHello -e 'print Hello::greet()'`;
    is($output, "Hello, World!", '-I switch includes directory');
    rmtree($tempdir);
}

# Test -0 (input record separator)
{
    my ($fh, $filename) = tempfile();
    print $fh "Hello\0World\0";
    close $fh;

    my $output = `$perl -0 -ne 'print \$_, "\n" ' $filename`;
    is($output, "Hello\0\nWorld\0\n", '-0 switch sets input record separator');
    unlink $filename;
}

# Test -a (autosplit mode)
{
    my ($fh, $filename) = tempfile();
    print $fh "Hello World\n";
    close $fh;

    my $output = `$perl -ane 'print \$main::F[1]' $filename`;
    is($output, "World", '-a switch enables autosplit mode');
    unlink $filename;
}

# Test -m (module import)
{
    my $output = `$perl -MData::Dumper -e 'print Dumper([1, 2, 3])'`;
    like($output, qr/\$VAR1 = \[\n\s+1,/, '-m switch imports module');
}

# Test -M (module import with arguments)
{
    my $output = `$perl -MData::Dumper=Dumper -e 'print Dumper([1, 2, 3])'`;
    like($output, qr/\$VAR1 = \[\n\s+1,/, '-M switch imports module with arguments');
}

# Test -h (help)
{
    my $output = `$perl -h 2>&1`;
    like($output, qr/Usage: /, '-h switch displays help');
}

# Test -? (help)
{
    my $output = `$perl -? 2>&1`;
    like($output, qr/Usage: /, '-? switch displays help');
}

# Test -F (split pattern)
{
    my ($fh, $filename) = tempfile();
    print $fh "Hello:World\nFoo:Bar\n";
    close $fh;

    my $output = `$perl -F: -ane ' print \$F[1] ' $filename`;
    is($output, "World\nBar\n", '-F switch splits input lines based on pattern');
    unlink $filename;
}

# Test -l (automatic line-ending processing)
{
    my ($fh, $filename) = tempfile();
    print $fh "Hello\nWorld\n";
    close $fh;

    my $output = `$perl -lpe '\$_ = uc' $filename`;
    is($output, "HELLO\nWORLD\n", '-l switch chomps input and appends output record separator');
    unlink $filename;
}

# Test -x (discard leading garbage)
{
    my ($fh, $filename) = tempfile();
    print $fh <<'END';
This is some leading garbage text.
More unrelated text.
#!perl
print "Extracted and executed!\n";
END
    close $fh;

    my $output = `$perl -x $filename`;
    is($output, "Extracted and executed!\n", '-x switch discards leading garbage and executes code');
    unlink $filename;
}

# Test -x with directory change
{
    my $tempdir = tempdir(CLEANUP => 1);
    my ($fh, $filename) = tempfile(DIR => $tempdir);
    print $fh <<'END';
Garbage text before the script.
#!perl
print "Running in the specified directory!\n";
END
    close $fh;

    my $output = `$perl -x$tempdir $filename`;
    is($output, "Running in the specified directory!\n", '-x switch changes directory and executes code');
    unlink $filename;
    rmtree($tempdir);
}

# Test -- (end of options)
{
    my ($fh, $filename) = tempfile();
    print $fh <<'END';
use strict;
use warnings;
print "Arguments: @ARGV\n";
END
    close $fh;

    my $output = `$perl $filename -- arg1 arg2`;
    is($output, "Arguments: -- arg1 arg2\n", '-- switch correctly handles end of options and script arguments');
    unlink $filename;
}

# Test -g (slurp mode)
{
    my ($fh, $filename) = tempfile();
    print $fh "Hello\nWorld\n";
    close $fh;

    my $output = `$perl -ne 'print length' $filename`;
    is($output, "66", '-n switch processes each line without printing');

    $output = `$perl -gne 'print length' $filename`;
    is($output, "12", '-g switch reads all input in one go');
    unlink $filename;
}
