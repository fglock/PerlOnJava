use v5.22;
use strict;
use warnings;
use feature 'refaliasing';
no warnings 'experimental::refaliasing';
use Test::More;

my ($left, $right) = (1, 2);

{
    my $weak_alias_target;
    our $weak_alias_reference;
    no warnings 'experimental::builtin';
    builtin::weaken($weak_alias_reference = \$weak_alias_target);
    \$weak_alias_target = $weak_alias_reference;
    is ref($weak_alias_reference), 'SCALAR',
        'weak reference to a live lexical remains valid for scalar ref aliasing';
}

{
    use feature 'declared_refs';
    my @localized_alias_source = (100, 200, 300);
    my @localized_alias_array = (1, 2, 3);
    my %localized_alias_hash = (one => 10, two => 20, three => 30);
    {
        local \(@localized_alias_array[0, 1, 2]) = \(@localized_alias_source);
        local \(@localized_alias_hash{qw(one two three)}) = \(@localized_alias_source);
        $localized_alias_source[0]++;
        is "@localized_alias_array", '101 200 300',
            'localized array slice aliases each referenced source slot';
        is "$localized_alias_hash{one} $localized_alias_hash{two} $localized_alias_hash{three}", '101 200 300',
            'localized hash slice aliases each referenced source slot';
    }
    is "@localized_alias_array", '1 2 3', 'localized array slice restores its slots';
    is "$localized_alias_hash{one} $localized_alias_hash{two} $localized_alias_hash{three}", '10 20 30',
        'localized hash slice restores its slots';
}

{
    no feature 'refaliasing';
    () = (1, 2);
    pass 'empty list assignment does not require the refaliasing feature';
}

{
    use warnings 'experimental::refaliasing';
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, shift };
    our ($warning_source, $warning_target);
    eval q{\$warning_target = \$warning_source};
    eval q{\($warning_target) = \$warning_source};
    is scalar @warnings, 2, 'direct and parenthesized ref aliases each warn once';
    like $warnings[0], qr/^Aliasing via reference is experimental/,
        'direct ref alias warning is experimental';
    like $warnings[1], qr/^Aliasing via reference is experimental/,
        'parenthesized ref alias warning is experimental';
}

our $parenthesized_package_target;
my $parenthesized_source = 7;
@_ = \$parenthesized_source;
(\$parenthesized_package_target) = @_;
is \$parenthesized_package_target, \$parenthesized_source,
    'one-member parenthesized package target consumes a reference list';

my $parenthesized_lexical_target;
(\$parenthesized_lexical_target) = @_;
is \$parenthesized_lexical_target, \$parenthesized_source,
    'one-member parenthesized lexical target consumes a reference list';

(\my $parenthesized_declared_target) = @_;
is \$parenthesized_declared_target, \$parenthesized_source,
    'one-member parenthesized declared target consumes a reference list';

our @parenthesized_local_target;
my @parenthesized_local_source = qw(local source);
my $parenthesized_local_original = \@parenthesized_local_target;
{
    (\local @parenthesized_local_target) = \@parenthesized_local_source;
    is \@parenthesized_local_target, \@parenthesized_local_source,
        'one-member parenthesized local array target aliases the source array';
}
is \@parenthesized_local_target, $parenthesized_local_original,
    'one-member parenthesized local array target unwinds its localization';

my %parenthesized_hash_source = (answer => 42);
(\my %parenthesized_hash_target) = \%parenthesized_hash_source;
is \%parenthesized_hash_target, \%parenthesized_hash_source,
    'one-member parenthesized declared hash target aliases the source hash';

our %parenthesized_local_hash_target;
my %parenthesized_local_hash_source = (local => 'hash');
my $parenthesized_local_hash_original = \%parenthesized_local_hash_target;
{
    (\local %parenthesized_local_hash_target) = \%parenthesized_local_hash_source;
    is \%parenthesized_local_hash_target, \%parenthesized_local_hash_source,
        'one-member parenthesized local hash target aliases the source hash';
}
is \%parenthesized_local_hash_target, $parenthesized_local_hash_original,
    'one-member parenthesized local hash target unwinds its localization';

{
    use feature 'lexical_subs';
    no warnings 'experimental::lexical_subs';
    sub lexical_sub_source { 'source' }
    my sub lexical_sub_target;
    \&lexical_sub_target = \&lexical_sub_source;
    is \&lexical_sub_target, \&lexical_sub_source,
        'direct lexical sub target aliases the source code reference';
    my sub parenthesized_lexical_sub_target;
    (\&parenthesized_lexical_sub_target) = (\&lexical_sub_source);
    is \&parenthesized_lexical_sub_target, \&lexical_sub_source,
        'parenthesized lexical sub target aliases the source code reference';

    my sub foreach_lexical_sub;
    my @foreach_lexical_sub_results;
    for \&foreach_lexical_sub(sub { 'first' }, sub { 'second' }) {
        push @foreach_lexical_sub_results, &foreach_lexical_sub;
    }
    is_deeply \@foreach_lexical_sub_results, [qw(first second)],
        'foreach lexical sub target aliases each source code reference';
}

my @foreach_declared_array_results;
for \my @foreach_declared_array([qw(one two)], [qw(three four)]) {
    push @foreach_declared_array_results, @foreach_declared_array;
}
is_deeply \@foreach_declared_array_results, [qw(one two three four)],
    'foreach declared array target dereferences each array reference';

our @foreach_shadowed_array = qw(package values);
my @foreach_shadowed_array_results;
for \my @foreach_shadowed_array([qw(one two)], [qw(three four)]) {
    push @foreach_shadowed_array_results, @foreach_shadowed_array;
}
is_deeply \@foreach_shadowed_array_results, [qw(one two three four)],
    'foreach declared array target shadows a same-named package array';

{
    no strict 'vars';
    my @foreach_implicit_package_source = qw(package values);
    \@foreach_implicit_package_array = \@foreach_implicit_package_source;
    my @foreach_implicit_package_results;
    for \my @foreach_implicit_package_array([qw(one two)], [qw(three four)]) {
        push @foreach_implicit_package_results, @foreach_implicit_package_array;
    }
    is_deeply \@foreach_implicit_package_results, [qw(one two three four)],
        'foreach declared array target shadows an implicitly created package array';
}

my @foreach_declared_hash_results;
for \my %foreach_declared_hash({ first => 1 }, { second => 2 }) {
    push @foreach_declared_hash_results, %foreach_declared_hash;
}
is_deeply [sort @foreach_declared_hash_results], [qw(1 2 first second)],
    'foreach declared hash target dereferences each hash reference';

{
    no warnings 'redefine';
    my @foreach_package_sub_results;
    for \&foreach_package_sub(sub { 'first' }, sub { 'second' }) {
        push @foreach_package_sub_results, &foreach_package_sub;
    }
is_deeply \@foreach_package_sub_results, [qw(first second)],
        'foreach package sub target aliases each source code reference';
}

{
    my $error;
    eval { my $target; \$target = 3 };
    $error = $@;
    like $error, qr/^Assigned value is not a reference at/,
        'scalar ref alias rejects a non-reference with Perl diagnostic';
    eval { my @target; \@target = {} };
    $error = $@;
    like $error, qr/^Assigned value is not an ARRAY reference at/,
        'array ref alias rejects the wrong reference type with Perl diagnostic';
    eval { my %target; \%target = [] };
    $error = $@;
    like $error, qr/^Assigned value is not a HASH reference at/,
        'hash ref alias rejects the wrong reference type with Perl diagnostic';
    eval { my $target; \$target = [] };
    $error = $@;
    like $error, qr/^Assigned value is not a SCALAR reference at/,
        'scalar ref alias rejects the wrong reference type with Perl diagnostic';
    eval { my sub target; \&target = [] };
    $error = $@;
    like $error, qr/^Assigned value is not a CODE reference at/,
        'code ref alias rejects the wrong reference type with Perl diagnostic';

    our (@alias_local, %alias_hash);
    for my $case (
        [ '(\\do{}) = 42', qr/^Can't modify reference to do block in list assignment at/ ],
        [ '(\\pos) = 42', qr/^Can't modify reference to match position in list assignment at/ ],
        [ '(\\glob) = 42', qr/^Can't modify reference to glob in list assignment at/ ],
        [ '\\(local @alias_local) = 42', qr/^Can't modify reference to localized parenthesized array in list assignment at/ ],
        [ '\\(%alias_hash) = 42', qr/^Can't modify reference to parenthesized hash in list assignment at/ ],
        [ '\\%{"42"} = 42', qr/^Can't modify reference to hash dereference in scalar assignment at/ ],
        [ '\\$0 =~ y/// = 0', qr/^Can't modify transliteration \(tr\/\/\/\) in scalar assignment at/ ],
    ) {
        eval $case->[0];
        like $@, $case->[1], "invalid ref alias target: $case->[0]";
    }
}

my $choose_left = 1;
$choose_left ? \$left : $right = \3;
is $left, 3, 'conditional target aliases an explicitly referenced true branch';
is $right, 2, 'conditional target leaves the unselected false branch alone';

$choose_left = 0;
my $reference_holder;
$choose_left ? \$left : $reference_holder = \6;
is $$reference_holder, 6, 'bare conditional branch receives the RHS reference value';

$choose_left = 0;
$choose_left ? \$left : \$right = \4;
is $right, 4, 'conditional target aliases an explicitly referenced false branch';

my $choose_nested = 1;
\($choose_nested ? $choose_left ? $left : $right : $right) = \5;
is $right, 5, 'nested conditional target binds the selected scalar slot';

my (@alias_list, $alias_scalar);
my ($first, $second, $third) = qw(first second third);
my $alias_count = ((\$alias_scalar, \(@alias_list)) = (\$first, \$second, \$third));
is $alias_count, 3, 'ref-alias list assignment returns its RHS count in scalar context';

my ($first_ref, $second_ref, $third_ref) = \($first, $second, $third);
((\$alias_scalar, \(@alias_list)) = ($first_ref, $second_ref, $third_ref)) = \(qw(replaced references only));
is $first_ref, \$first, 'outer assignment changes only the ref-alias expression temporaries';
is $second_ref, \$second, 'outer assignment preserves the second source reference';
is $third_ref, \$third, 'outer assignment preserves the aggregate source reference';

our ($mixed_alias_value, $mixed_reference_value);
(\$mixed_alias_value, $mixed_reference_value) = \(1, 2);
is "$mixed_alias_value $$mixed_reference_value", '1 2',
    'mixed ref-alias and ordinary scalar list members retain their distinct semantics';

{
    my @state_hash_values;
    for my $iteration (1, 2) {
        \state %state_alias_hash = { value => $iteration };
        push @state_hash_values, $state_alias_hash{value};
    }
    is_deeply \@state_hash_values, [1, 1],
        'state hash ref alias retains its initial hash binding across iterations';
}

{
    my ($original, $temporary);
    my %localized_hash;
    \$localized_hash{value} = \$original;
    {
        \local $localized_hash{value} = \$temporary;
    }
    is \$localized_hash{value}, \$original,
        'localized hash ref alias restores the original scalar slot identity';
}

{
    no strict 'vars';
    for \my $topic(\$for1, \$for2) {
        push @for, \$topic;
    }
    @for = ();
    for \$::a(\$for1, \$for2) {
        push @for, \$::a;
    }
    @for = ();
    for \my @a([1,2], [3,4]) {
        push @for, @a;
    }
    is_deeply \@for, [1, 2, 3, 4],
        'foreach declared array alias refreshes each iteration before a global push';
}

{
    no strict 'refs';
    no strict 'vars';
    \$lvref_glob_target = \*lvref_glob_source;
    is *lvref_glob_target{SCALAR}, *lvref_glob_source{GLOB},
        'refaliasing a scalar to a glob installs the glob scalar slot';
}

{
    my @state_scalar_results;
    for (1, 2) {
        \my $state_scalar_lexical = \3,
        \my($state_scalar_grouped_lexical) = \3,
        \state $state_scalar_direct = \3,
        \state($state_scalar_grouped) = \3 if $_ == 1;
        \state $state_scalar_loop_value = \$_;
        if ($_ == 2) {
            push @state_scalar_results,
                $state_scalar_lexical,
                $state_scalar_grouped_lexical,
                $state_scalar_direct,
                $state_scalar_grouped,
                $state_scalar_loop_value;
        }
    }
    is_deeply \@state_scalar_results, [undef, undef, 3, 3, 1],
        'mixed lexical and state scalar refaliases retain their scope and initialization semantics';
}

{
    \state @state_shadowed_loop_alias = [qw(stale values)];
    my @state_shadowed_loop_results;
    for \my @state_shadowed_loop_alias([qw(one two)], [qw(three four)]) {
        push @state_shadowed_loop_results, @state_shadowed_loop_alias;
    }
    is_deeply \@state_shadowed_loop_results, [qw(one two three four)],
        'foreach lexical array alias shadows a same-named state array';
}

sub forward_jump_refalias_value {
    my @forward_alias_array;
    goto install_forward_aliases;

write_forward_aliases:
    @forward_alias_array[0, 1] = qw(a b);
    my ($forward_right, $forward_left) = @forward_alias_array[0, 1];
    return join ' ', @forward_alias_array;

install_forward_aliases:
    \(@forward_alias_array) = \($forward_left, $forward_right);
    goto write_forward_aliases;
}

is forward_jump_refalias_value(), 'b a',
    'forward-jump refalias materializes lexical scalar cells before binding them';

{
    use feature 'lexical_subs', 'signatures', 'state';
    no warnings 'experimental::lexical_subs', 'experimental::signatures';
    my $state_refalias_seed;
    my sub state_refalias_skipped_pad ($arg) {
        state $state_refalias_value = ++$state_refalias_seed;
        return $state_refalias_seed if $arg == 3;
        goto skipped_state_refalias_declaration if $arg == 2;
        my $state_refalias_skipped_pad;
    skipped_state_refalias_declaration:
        \$state_refalias_value = \$state_refalias_skipped_pad if $arg == 2;
    }
    state_refalias_skipped_pad(1);
    is ref state_refalias_skipped_pad(2), 'SCALAR',
        'state refalias can bind a pad slot reached before its declaration';
    is state_refalias_skipped_pad(3), 1,
        'state refalias retains its initialized state after a skipped-pad alias';
}

{
    use feature 'lexical_subs';
    no warnings 'experimental::lexical_subs';
    my $closure_state_seed;
    my sub closure_state_refalias_capture {
        state $closure_state_value = ++$closure_state_seed;
        \($closure_state_value) = \($closure_state_seed);
        return $closure_state_seed;
    }
    is closure_state_refalias_capture(), 1,
        'lexical sub captures a writable outer scalar before state refaliasing';
    is closure_state_refalias_capture(), 1,
        'lexical sub preserves its captured scalar and state cell across calls';
}

done_testing;
