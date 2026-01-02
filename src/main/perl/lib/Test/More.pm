package Test::More;

use strict;
use warnings;
use Symbol 'qualify_to_ref';
use Data::Dumper;

# Load Test::Builder to make it available for legacy tests
use Test::Builder;

# Test::Builder singleton
our $Test_Builder;

our @EXPORT = qw(
    plan ok is isnt like unlike cmp_ok can_ok isa_ok
    pass fail diag note done_testing is_deeply subtest
    use_ok require_ok BAIL_OUT
    skip
    eq_array eq_hash eq_set
);

our $Test_Count = 0;
our $Plan_Count;
our $Failed_Count = 0;
our $Test_Indent = "";

sub import {
    my $package = shift;
    my $caller = caller;
    if (@_) {
        plan(@_);
    }

    for my $symbol (@EXPORT) {
        my $full_name = Symbol::qualify_to_ref($symbol, $caller);
        *$full_name = \&{$symbol};
    }
}

sub plan {
    my ($directive, $arg) = @_;
    if ($directive eq 'tests') {
        $Plan_Count = $arg;
        print "${Test_Indent}1..$Plan_Count\n";
    }
    elsif ($directive eq 'skip_all') {
        print "${Test_Indent}1..0 # Skipped: $arg\n";
        exit 0;
    }
}

sub ok ($;$) {
    my ($test, $name) = @_;
    $Test_Count++;
    my $result = $test ? "ok" : "not ok";
    $Failed_Count++ unless $test;
    print "$Test_Indent$result $Test_Count - $name\n";
    return $test;
}

sub is ($$;$) {
    my ($got, $expected, $name) = @_;
    my $test = (!defined $got && !defined $expected) ||
        (defined $got && defined $expected && $got eq $expected);
    ok($test, $name);
    unless ($test) {
        diag("         got: " . (defined $got ? "'$got'" : "undef"));
        diag("    expected: " . (defined $expected ? "'$expected'" : "undef"));
    }
    return $test;
}

sub isnt ($$;$) {
    my ($got, $expected, $name) = @_;
    my $test = (!defined $got && !defined $expected) ||
        (defined $got && defined $expected && $got eq $expected);
    $test = !$test;
    ok($test, $name);
    unless ($test) {
        diag("         got: '$got'");
        diag("    expected: anything else");
    }
    return $test;
}

sub like ($$;$) {
    my ($got, $regex, $name) = @_;
    my $test = defined $got && $got =~ /$regex/;
    ok($test, $name);
    unless ($test) {
        diag("                  '$got'");
        diag("    doesn't match '$regex'");
    }
    return $test;
}

sub unlike ($$;$) {
    my ($got, $regex, $name) = @_;
    my $test = !defined $got || $got !~ /$regex/;
    ok($test, $name);
    unless ($test) {
        diag("                  '$got'");
        diag("          matches '$regex'");
    }
    return $test;
}

sub cmp_ok ($$$;$){
    my ($got, $op, $expected, $name) = @_;
    my $test = eval "$got $op $expected";
    ok($test, $name);
    unless ($test) {
        diag("         got: $got");
        diag("    expected: $expected");
    }
    return $test;
}

sub can_ok ($@) {
    my ($module, @methods) = @_;
    my $test = 1;
    for my $method (@methods) {
        unless ($module->can($method)) {
            $test = 0;
            diag("    $module cannot '$method'");
        }
    }
    ok($test, "$module can do everything we're asking");
    return $test;
}

sub isa_ok ($$;$) {
    my ($object, $class, $name) = @_;
    $name ||= "The object";
    my $test = UNIVERSAL::isa($object, $class);
    ok($test, "$name isa $class");
    return $test;
}

sub pass (;$) {ok(1, $_[0])}
sub fail (;$) {ok(0, $_[0])}

sub diag {
    my ($message) = @_;
    print STDERR "$Test_Indent# $message\n";
}

sub note {
    my ($message) = @_;
    return unless defined $message;
    print "$Test_Indent# $message\n";
}

sub done_testing {
    my ($count) = @_;
    $count ||= $Test_Count;

    if ($Plan_Count && $Plan_Count != $Test_Count) {
        ok(0, "planned to run $Plan_Count but done_testing() expects $Test_Count");
        diag("   Failed test 'planned to run $Plan_Count but done_testing() expects $Test_Count'");
        diag("   at $0 line " . (caller)[2] . ".");
        diag("Looks like you failed 1 test of $Plan_Count.");
        return 0;
    }

    print "${Test_Indent}1..$count\n" unless $Plan_Count;
    return $Failed_Count == 0;
}

sub is_deeply {
    my ($got, $expected, $name) = @_;
    local $Data::Dumper::Sortkeys = 1;
    my $got_dumped = Dumper($got);
    my $expected_dumped = Dumper($expected);
    my $test = $got_dumped eq $expected_dumped;
    ok($test, $name);
    unless ($test) {
        diag("         got: $got_dumped");
        diag("    expected: $expected_dumped");
    }
    return $test;
}

sub subtest {
    my ($name, $code) = @_;
    print "# Subtest: $name\n";
    my $result;

    local $Plan_Count;
    {
        # Reset counters for subtest and set indent
        local $Failed_Count = 0;
        local $Test_Count = 0;
        local $Test_Indent = $Test_Indent . "    ";

        # Run the subtest code
        $code->();

        # Print subtest plan
        # print "${Test_Indent}1..$Test_Count\n";
        $result = $Failed_Count == 0;
    }

    # Report subtest result
    ok($result, $name);
    return $result;
}

sub require_ok {
    my ($module_or_file) = @_;
    my $test_name;

    # Determine if it's a module name or file path
    if ($module_or_file =~ /\.pl$/ || $module_or_file =~ /\//) {
        # It's a file
        $test_name = "require '$module_or_file'";
    } else {
        # It's a module name
        $test_name = "require $module_or_file";
        # Convert module name to file path for require
        my $file = $module_or_file;
        $file =~ s/::/\//g;
        $file .= '.pm';
        $module_or_file = $file;
    }

    my $result = eval {
        require $module_or_file;
        1;
    };

    if ($result) {
        ok(1, $test_name);
    } else {
        ok(0, $test_name);
        my $error = $@ || 'Unknown error';
        # Clean up the error message
        $error =~ s/\n$//;
        diag("Error loading $module_or_file: $error");
    }

    return $result;
}

sub use_ok {
    my ($module, @imports) = @_;
    my $test_name = "use $module";

    # Check if first import is a version number
    my $version;
    if (@imports && $imports[0] =~ /^[\d\.]+$/) {
        $version = shift @imports;
        $test_name .= " $version";
    }

    if (@imports) {
        $test_name .= " qw(" . join(' ', @imports) . ")";
    }

    # Build the use statement
    my $use_statement = "package " . caller() . "; use $module";
    $use_statement .= " $version" if $version;
    $use_statement .= " qw(" . join(' ', @imports) . ")" if @imports;

    my $result = eval $use_statement;

    if (defined $result || $@ eq '') {
        ok(1, $test_name);
        return 1;
    } else {
        ok(0, $test_name);
        my $error = $@ || 'Unknown error';
        # Clean up the error message
        $error =~ s/\n$//;
        diag("Error loading $module: $error");
        return 0;
    }
}

sub BAIL_OUT {
    my ($reason) = @_;
    my $msg = "Bail out!";
    $msg .= "  $reason" if defined $reason && length $reason;
    print "$msg\n";
    exit 255;
}

sub skip {
    my $why = shift;
    my $n   = @_ ? shift : 1;
    my $bad_swap;
    my $both_zero;
    {
        local $^W = 0;
        $bad_swap = $why > 0 && $n == 0;
        $both_zero = $why == 0 && $n == 0;
    }
    if ($bad_swap || $both_zero || @_) {
        my $arg = "'$why', '$n'";
        if (@_) {
            $arg .= join(", ", '', map { qq['$_'] } @_);
        }
        die qq[$0: expected skip(why, count), got skip($arg)\n];
    }
    for (1..$n) {
        ok(1, "$test # skip $why");
    }
    local $^W = 0;
    last SKIP;
}

# Legacy comparison functions - simple implementations using is_deeply
sub eq_array($$) {
    my ($got, $expected) = @_;
    local $Test::Builder::Level = $Test::Builder::Level + 1;
    return is_deeply($got, $expected);
}

sub eq_hash($$) {
    my ($got, $expected) = @_;
    local $Test::Builder::Level = $Test::Builder::Level + 1;
    return is_deeply($got, $expected);
}

sub eq_set($$) {
    my ($got, $expected) = @_;
    local $Test::Builder::Level = $Test::Builder::Level + 1;
    # Set comparison: order doesn't matter
    # Simple implementation: sort and compare
    my @got_sorted = sort { (defined $a ? $a : '') cmp (defined $b ? $b : '') } @$got;
    my @expected_sorted = sort { (defined $a ? $a : '') cmp (defined $b ? $b : '') } @$expected;
    return is_deeply(\@got_sorted, \@expected_sorted);
}

# Provide access to Test::Builder for legacy tests
sub builder {
    $Test_Builder ||= Test::Builder->new();
    return $Test_Builder;
}

1;
