use strict;
use warnings;
use Test::More;
use IPC::Open3 qw(open3);
use Symbol qw(gensym);

for my $case (
    [ q{'}, 'Int', q{'}, q{Bad name after Int'} ],
    [ q{"}, 'Foo::$subpackage', q{"}, q{Bad name after Foo::} ],
) {
    my ($opening, $value, $closing, $bad_name) = @$case;
    my $source = "sub has{}\nhas erdef => (\n    isa => 'Int',\n    is => ${opening}ro,\n    default => sub { 1 }\n);\n\nhas cxxc => (\n    isa => ${opening}${value}${closing},\n    is => 'ro',\n    default => sub { 1 }\n);";
    my $launcher = $^X eq 'jperl' ? './jperl' : $^X;
    my $stderr = gensym;
    my $pid = open3(undef, my $stdout, $stderr, $launcher, '-e', $source);
    my $output = do { local $/; <$stdout> } . do { local $/; <$stderr> };
    waitpid $pid, 0;

    my $near = "isa => ${opening}" . ($value =~ /\$/ ? 'Foo' : 'Int');
    like($output,
        qr/Bareword found where operator expected \(Do you need to predeclare "isa"\?\) at -e line 9, near "\Q$near\E"\n  \(Might be a runaway multi-line \Q$opening$closing\E string starting on line 4\)\n\Q$bad_name\E at -e line 9\./,
        "malformed $opening attribute quote retains the later isa diagnostic");
}

done_testing;
