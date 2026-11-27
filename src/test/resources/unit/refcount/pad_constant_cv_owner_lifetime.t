use strict;
use warnings;
use Scalar::Util qw(weaken refaddr);
use Test::More;

{
    no warnings 'redefine';
    sub pad_constant_owner_target { return \q(owned) }
}

my $old_cv = \&pad_constant_owner_target;
my $strong = $old_cv->();
my $weak = $strong;
weaken($weak);
undef $strong;

{
    no warnings 'redefine';
    *pad_constant_owner_target = sub { return undef };
}

ok(defined $weak, 'the old CV keeps its pad constant alive after glob replacement');
my $same_constant = $old_cv->();
ok(defined $weak && refaddr($weak) == refaddr($same_constant),
    'the retained CV still returns the same constant scalar');
undef $same_constant;
ok(defined $weak, 'a returned weakly observed constant survives call cleanup');

undef $old_cv;
ok(!defined $weak, 'releasing the last old-CV owner reaps its pad constant');

{
    no warnings 'redefine';
    sub pad_constant_external_owner_target { return \q(external) }
}

my $external_cv = \&pad_constant_external_owner_target;
my $external_strong = $external_cv->();
my $external_weak = $external_strong;
weaken($external_weak);
{
    no warnings 'redefine';
    undef &pad_constant_external_owner_target;
}
undef $external_cv;
ok(defined $external_weak,
    'a caller reference keeps the constant alive after its CV is released');
undef $external_strong;
ok(!defined $external_weak,
    'the constant is reaped when its final caller reference leaves');

{
    no warnings 'redefine';
    sub pad_constant_factory { return sub { return \q(shared) } }
}

my $factory = \&pad_constant_factory;
my $first_closure = $factory->();
my $template_weak = $first_closure->();
weaken($template_weak);
undef $first_closure;
ok(defined $template_weak,
    'the factory CV keeps its nested optree constant alive between closures');

my $second_closure = $factory->();
my $second_constant = $second_closure->();
ok(defined $template_weak && refaddr($template_weak) == refaddr($second_constant),
    'later closure instances share the nested optree constant identity');
undef $second_constant;
undef $second_closure;
ok(defined $template_weak,
    'the factory template remains the owner after a closure instance is released');

{
    no warnings 'redefine';
    undef &pad_constant_factory;
}
undef $factory;
ok(!defined $template_weak,
    'releasing the factory and its final closure owner reaps the nested constant');

done_testing();
