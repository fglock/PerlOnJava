use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);

my $skip_launcher = $^O eq 'MSWin32'
    || ($^X eq 'jperl' && !-f 'target/perlonjava-5.42.0.jar');

sub run_interpreter_child {
    my ($code) = @_;

    my ($script_fh, $script_name) = tempfile(SUFFIX => '.pl');
    print {$script_fh} $code;
    close $script_fh or die "close child script: $!";

    my ($out_fh, $out_name) = tempfile();
    my $jperl = $^X eq 'jperl' ? './jperl' : $^X;

    open(my $saved_stdout, '>&', \*STDOUT) or die "save stdout: $!";
    open(my $saved_stderr, '>&', \*STDERR) or die "save stderr: $!";
    open(STDOUT, '>&', $out_fh) or die "redirect stdout: $!";
    open(STDERR, '>&', $out_fh) or die "redirect stderr: $!";
    my $status = system('timeout', '60', $jperl, '--interpreter', $script_name);
    open(STDERR, '>&', $saved_stderr) or die "restore stderr: $!";
    open(STDOUT, '>&', $saved_stdout) or die "restore stdout: $!";
    close $saved_stderr;
    close $saved_stdout;

    seek($out_fh, 0, 0);
    my $output = do { local $/; <$out_fh> };
    close $out_fh;
    unlink $out_name;
    unlink $script_name;

    return ($status, $output);
}

SKIP: {
    skip 'nested jperl launcher is unavailable', 8 if $skip_launcher;

    my ($delete_status, $delete_output) = run_interpreter_child(<<'END_CHILD');
use strict;
use warnings;
my $rs_data = { a => 1, b => 2 };
my $colnames = ['a'];
delete @{$rs_data}{@$colnames};
print join(',', sort keys %$rs_data), "\n";
END_CHILD

    is($delete_status, 0, 'interpreter hashref slice delete exits successfully')
        or diag $delete_output;
    is($delete_output, "b\n",
        'interpreter delete @{$hashref}{@$arrayref} deletes expanded keys');

    my ($coderef_status, $coderef_output) = run_interpreter_child(<<'END_CHILD');
use strict;
use warnings;
BEGIN {
    package InterpreterDBICRegression::Exporter;
    sub f { 42 }
    sub import {
        my $target = caller;
        no strict 'refs';
        *{"${target}::f"} = \&f;
    }
    $INC{'InterpreterDBICRegression/Exporter.pm'} = 1;
}
package InterpreterDBICRegression::Consumer;
use InterpreterDBICRegression::Exporter;
sub g { &f }
BEGIN { delete $InterpreterDBICRegression::Consumer::{f} }
package main;
print InterpreterDBICRegression::Consumer::g(), "\n";
END_CHILD

    is($coderef_status, 0, 'interpreter pinned &sub call exits successfully')
        or diag $coderef_output;
    is($coderef_output, "42\n",
        'interpreter &sub call keeps parse-time CV after stash cleanup');

    my ($return_status, $return_output) = run_interpreter_child(<<'END_CHILD');
use strict;
use warnings;
sub g { return wantarray ? (1, 2, 19) : 1 }
sub f { return g() }
my $x = f();
my @y = f();
print "x=$x y=@y\n";
END_CHILD

    is($return_status, 0, 'interpreter explicit return subcall exits successfully')
        or diag $return_output;
    is($return_output, "x=1 y=1 2 19\n",
        'interpreter evaluates return subcall in caller context');

    my ($return_list_status, $return_list_output) = run_interpreter_child(<<'END_CHILD');
use strict;
use warnings;
my @seen;
sub g {
    push @seen, wantarray ? 'list' : 'scalar';
    return wantarray ? (1, 2, 19) : 1;
}
sub f { return (g(), 5) }
my $x = f();
my @y = f();
print "x=$x y=@y seen=@seen\n";
END_CHILD

    is($return_list_status, 0, 'interpreter explicit return list exits successfully')
        or diag $return_list_output;
    is($return_list_output, "x=5 y=1 2 19 5 seen=scalar list\n",
        'interpreter evaluates return list elements in caller context');
}

done_testing;
