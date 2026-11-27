use strict;
use warnings;
use Scalar::Util qw(isweak weaken);
use Test::More;
use feature 'signatures';
no warnings 'experimental::signatures';

my ($weak_array, $weak_hash);
{
    my ($array_prefix, @items) = (0, qw(first second));
    my ($hash_prefix, %metadata) = (0, answer => 42);

    $weak_array = \@items;
    $weak_hash = \%metadata;
    weaken($weak_array);
    weaken($weak_hash);

    ok(isweak($weak_array), 'the array observer remains weak');
    ok(isweak($weak_hash), 'the hash observer remains weak');
    ok(defined($weak_array), 'a list-declared lexical array owns its weak referent');
    ok(defined($weak_hash), 'a list-declared lexical hash owns its weak referent');
    is_deeply($weak_array, [qw(first second)], 'the live array remains accessible');
    is_deeply($weak_hash, { answer => 42 }, 'the live hash remains accessible');
}

ok(!defined($weak_array), 'the weak array observer clears after lexical scope exit');
ok(!defined($weak_hash), 'the weak hash observer clears after lexical scope exit');

our ($signature_array_weak, $signature_hash_weak);
sub retain_signature_array (@items) {
    $signature_array_weak = \@items;
    weaken($signature_array_weak);
    ok(isweak($signature_array_weak), 'the signature array observer remains weak');
    ok(defined($signature_array_weak), 'the slurpy array pad owns its weak referent');
    is_deeply($signature_array_weak, [qw(left right)], 'the signature array remains accessible');
}

sub retain_signature_hash (%metadata) {
    $signature_hash_weak = \%metadata;
    weaken($signature_hash_weak);
    ok(isweak($signature_hash_weak), 'the signature hash observer remains weak');
    ok(defined($signature_hash_weak), 'the slurpy hash pad owns its weak referent');
    is_deeply($signature_hash_weak, { answer => 42 }, 'the signature hash remains accessible');
}

retain_signature_array(qw(left right));
retain_signature_hash(answer => 42);
ok(!defined($signature_array_weak), 'the signature array observer clears after frame exit');
ok(!defined($signature_hash_weak), 'the signature hash observer clears after frame exit');

done_testing;
