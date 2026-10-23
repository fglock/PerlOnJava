use strict;
use warnings;
use Test::More;
use Hash::Util qw(
    lock_keys unlock_keys lock_keys_plus lock_hash unlock_hash
    lock_value unlock_value hashref_locked hash_locked hash_unlocked
    legal_keys hidden_keys all_keys
);

my %hash = (present => 1);
lock_keys(%hash, qw(present permitted));
ok(hashref_locked(\%hash), 'lock_keys marks the hash restricted');
is_deeply([sort(legal_keys(%hash))], [qw(permitted present)], 'legal_keys includes allowed absent keys');
is_deeply([sort(hidden_keys(%hash))], ['permitted'], 'hidden_keys reports allowed absent keys');
ok(hash_locked(%hash), 'hash_locked recognizes restricted keys');
ok(Internals::SvREADONLY(%hash), 'restricted hash reports readonly through SvREADONLY');
ok(!$hash{permitted}, 'allowed absent key can be read');
$hash{permitted} = 2;
is($hash{permitted}, 2, 'allowed absent key can be created');
ok(grep { $_ eq 'present' } keys %hash, 'keys returns existing keys');
my (@all_keys, @unused_keys);
my $all_keys_ref = all_keys(%hash, @all_keys, @unused_keys);
is($all_keys_ref, \%hash, 'all_keys returns the hash reference');
is_deeply([sort @all_keys], [qw(permitted present)], 'all_keys fills the existing-key output');
is_deeply([sort @unused_keys], [], 'all_keys fills the hidden-key output');

for my $operation (
    ['read', sub { my $value = $hash{forbidden} }],
    ['write', sub { $hash{forbidden} = 3 }],
    ['delete', sub { delete $hash{forbidden} }],
) {
    my ($name, $code) = @$operation;
    eval { $code->(); 1 };
    like($@, qr/Attempt to (?:access|delete) disallowed key 'forbidden' .*restricted hash/,
        "$name of a disallowed key is rejected");
}

eval { lock_keys(%hash, 'present') };
like($@, qr/Hash has key 'permitted' which is not in the new key set/,
    'lock_keys rejects a key set that omits an existing key');
unlock_keys(%hash);
ok(!hashref_locked(\%hash), 'unlock_keys removes the key restriction');
$hash{new_key} = 4;
is($hash{new_key}, 4, 'ordinary keys can be added after unlock_keys');

lock_keys_plus(%hash, 'extra');
$hash{extra} = 5;
is($hash{extra}, 5, 'lock_keys_plus adds permitted keys');
eval { my $value = $hash{not_extra} };
like($@, qr/Attempt to access disallowed key 'not_extra'/, 'lock_keys_plus still rejects other keys');
unlock_keys(%hash);

lock_keys(%hash);
lock_value(%hash, 'present');
ok(hash_locked(%hash), 'lock_value marks a value locked');
eval { $hash{present} = 7 };
like($@, qr/read-only value/, 'locked value cannot be assigned');
eval { delete $hash{present} };
like($@, qr/Attempt to delete readonly key 'present'/, 'locked value cannot be deleted');
eval { %hash = (permitted => 6) };
like($@, qr/Attempt to delete readonly key 'present'/, 'whole-hash assignment cannot remove a locked value');
unlock_value(%hash, 'present');
$hash{present} = 7;
is($hash{present}, 7, 'unlock_value restores assignment');
ok(!hash_unlocked(%hash), 'hash_unlocked remains false while keys are restricted');
unlock_keys(%hash);
ok(hash_unlocked(%hash), 'unlock_keys reports the hash unlocked');

lock_hash(%hash);
ok(hash_locked(%hash), 'lock_hash locks hash values');
eval { my $value = $hash{missing} };
like($@, qr/Attempt to access disallowed key 'missing'/, 'lock_hash rejects missing keys');
eval { $hash{present} = 8 };
like($@, qr/read-only value/, 'lock_hash prevents value assignment');
unlock_hash(%hash);
$hash{missing} = 9;
is($hash{missing}, 9, 'unlock_hash restores ordinary hash behavior');

done_testing;
