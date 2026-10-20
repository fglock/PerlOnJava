use strict;
use warnings;
use Test::More tests => 2;
use Pod::Simple;

{
    package Local::PodStripFixture;
    our @ISA = ('Pod::Simple');

    sub new {
        my $self = shift->SUPER::new(@_);
        $self->{code_line} = 0;
        $self->code_handler(sub {
            my ($line, $line_number, $parser) = @_;
            if ($parser->{replace_with_comments}) {
                if ($parser->{code_line} + 1 < $line_number) {
                    print {$parser->{output_fh}}
                        ("# stripped POD\n") x ($line_number - $parser->{code_line} - 1);
                }
                $parser->{code_line} = $line_number;
            }
            print {$parser->{output_fh}} $line, "\n";
        });
        return $self;
    }

    sub replace_with_comments {
        my ($self, $value) = @_;
        $self->{replace_with_comments} = defined $value ? $value : 1;
        return $self->{replace_with_comments};
    }
}

my $source = <<'SOURCE';
#!/usr/bin/perl


=pod

the great hello world script

including lots of POD

=cut

# print it
print "Hello, world!\n";

exit "done";

=pod

more pod

=cut
SOURCE
$source .= "\n";

my $podless;
my $parser = Local::PodStripFixture->new;
$parser->replace_with_comments(1);
$parser->output_string(\$podless);
$parser->parse_string_document($source);

my $expected = <<'EXPECTED';
#!/usr/bin/perl


# stripped POD
# stripped POD
# stripped POD
# stripped POD
# stripped POD
# stripped POD
# stripped POD

# print it
print "Hello, world!\n";

exit "done";

# stripped POD
# stripped POD
# stripped POD
# stripped POD
# stripped POD

EXPECTED

is($parser->replace_with_comments(), 1, 'comment replacement mode is enabled');
is($podless, $expected, 'code handler line positions preserve one replacement comment per POD source line');
