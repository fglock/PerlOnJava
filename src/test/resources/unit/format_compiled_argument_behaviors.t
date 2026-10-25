use strict;
use warnings;
use Test::More;

{
    package FormatArgumentTie;
    sub TIESCALAR { bless { fetches => $_[1] }, $_[0] }
    sub FETCH { ${$_[0]{fetches}}++; return 'tied' }

    package FormatArgumentOverload;
    use overload '""' => sub { return 'overloaded' }, fallback => 1;
}

my $fetches = 0;
tie my $tied_value, 'FormatArgumentTie', \$fetches;
my $overloaded_value = bless {}, 'FormatArgumentOverload';
format FORMAT_COMPILED_ARGUMENT_BEHAVIORS =
@<<<<<<<<<<<<<<
$tied_value . ':' . $overloaded_value
.

my $path = 'format_compiled_argument_behaviors.tmp';
open my $fh, '>', $path or die "open $path: $!";
my $old_fh = select((select($fh), $~ = 'FORMAT_COMPILED_ARGUMENT_BEHAVIORS')[0]);
write $fh;
select $old_fh;
close $fh or die "close $path: $!";
open my $read_fh, '<', $path or die "read $path: $!";
my $rendered = do { local $/; <$read_fh> };
close $read_fh or die "close $path after read: $!";
unlink $path or die "unlink $path: $!";
is($rendered, "tied:overloaded\n", 'format expression preserves tie and overload behavior');
is($fetches, 1, 'format expression fetches a tied value once');

sub warn_from_format { warn "format expression warning\n"; return 'ok' }
format FORMAT_COMPILED_ARGUMENT_WARNING =
@<<
warn_from_format()
.
my $warning = '';
{
    local $SIG{__WARN__} = sub { $warning .= join '', @_ };
    $~ = 'FORMAT_COMPILED_ARGUMENT_WARNING';
    write;
}
like($warning, qr/format expression warning/, 'format expression propagates warnings');

sub die_from_format { die "format expression failure\n" }
format FORMAT_COMPILED_ARGUMENT_ERROR =
@<<
die_from_format()
.
open my $error_fh, '>', $path or die "open $path for error case: $!";
$old_fh = select((select($error_fh), $~ = 'FORMAT_COMPILED_ARGUMENT_ERROR')[0]);
my $write_ok = eval { write $error_fh; 1 };
my $format_error = $@;
select $old_fh;
close $error_fh or die "close $path after error case: $!";
unlink $path or die "unlink $path after error case: $!";
ok(!$write_ok, 'format expression error aborts write');
like($format_error, qr/format expression failure/,
    'format expression propagates Perl errors');

done_testing;
