use strict;
use warnings;
use Test::More tests => 6;

our $destroy_error;
{
    package CoreGate::DestroyReadonly;
    sub DESTROY {
        eval { $_[0] = 'replaced' };
        $main::destroy_error = $@;
    }
}
my $object = bless {}, 'CoreGate::DestroyReadonly';
undef $object;
like($destroy_error, qr/^Modification of a read-only value attempted/,
     'assignment cannot replace the synthetic DESTROY invocant');

our $destroy_default;
{
    package CoreGate::DestroyDefault;
    sub DESTROY {
        $_ = 'bad';
        s/bad/ok/;
        $main::destroy_default = $_;
    }
}
my $default_object = bless {}, 'CoreGate::DestroyDefault';
undef $default_object;
is($destroy_default, 'ok',
   'a DESTROY body can still assign to the default variable');

my $path = 'core_gate_runtime_regressions.tmp';
open(my $writer, '>', $path) or die "open $path: $!";
print {$writer} "content\n";
close($writer) or die "close $path: $!";

sub open_through_localized_glob {
    local(*CORE_GATE_FH) = @_;
    open(CORE_GATE_FH, $path) or die "open $path: $!";
    ok(!eof CORE_GATE_FH ? 1 : 0,
       'eof BAREHANDLE leaves the following ternary outside its operand');
}

open_through_localized_glob(*CORE_GATE_FH);
ok(defined fileno(CORE_GATE_FH),
   'local glob assignment aliases the passed filehandle glob');
ok(!eof(CORE_GATE_FH), 'aliased caller handle remains positioned before content');
close(CORE_GATE_FH) or die "close $path: $!";
unlink($path) or die "unlink $path: $!";

{
    package CoreGate::TieHints;
    sub TIEHASH  { bless [] }
    sub STORE    { $_[0][0]{$_[1]} = $_[2] }
    sub FETCH    { $_[0][0]{$_[1]} }
    sub FIRSTKEY { my $count = keys %{$_[0][0]}; each %{$_[0][0]} }
    sub NEXTKEY  { each %{$_[0][0]} }
}

our $inner_hint;
my $compiled = eval q{
    BEGIN {
        $^H{core_gate} = 'outer';
        tie(%^H, 'CoreGate::TieHints');
        $^H{core_gate} = 'outer';
    }
    {
        BEGIN {
            %^H = ();
            tie(%^H, 'CoreGate::TieHints');
            $^H{core_gate} = 'inner';
        }
        { BEGIN { $main::inner_hint = $^H{core_gate} } }
    }
    1;
};
die $@ unless $compiled;
is($inner_hint, 'inner', 'an emptied and retied hint hash clones into an inner scope');
