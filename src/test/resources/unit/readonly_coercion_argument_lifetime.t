use strict;
use warnings;
use Test::More;
use Storable qw(dclone);

# This is the ownership decision made by Const::Fast before it freezes a
# coercion result: clone a shared aggregate. Keep it behind an eval-created
# callback, matching the generated Type::Tiny coercion path which used to
# clean up the caller's aggregate as though it belonged to the callback.
sub freeze_for_coercion {
    my ($value) = @_;
    $value = dclone($value) if &Internals::SvREFCNT($value) > 1;
    return $value;
}

my $coerce = eval q{
    sub {
        my $value = @_ ? $_[0] : $_;
        return freeze_for_coercion($value);
    }
};
die $@ if $@;

my @values = (1, 2);
my $readonly_array = $coerce->(\@values);
ok !&Internals::SvREADONLY(\@values),
    'generated coercion leaves the caller array writable';
isnt $readonly_array, \@values,
    'generated coercion clones its array result';
ok eval { $values[0]++; 1 }, 'caller array remains mutable';
is $readonly_array->[0], 1, 'cloned array is independent of caller mutation';

my %values = (answer => 42);
my $readonly_hash = $coerce->(\%values);
ok !&Internals::SvREADONLY(\%values),
    'generated coercion leaves the caller hash writable';
isnt $readonly_hash, \%values,
    'generated coercion clones its hash result';
ok eval { $values{answer}++; 1 }, 'caller hash remains mutable';
is $readonly_hash->{answer}, 42, 'cloned hash is independent of caller mutation';

done_testing;
