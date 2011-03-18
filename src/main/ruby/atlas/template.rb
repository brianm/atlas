require 'java'

module Atlas

  module Template
    include_package "com.ning.atlas.template"

    class SystemTemplateParser

      def initialize template_path
        @template = File.read template_path
        @template_path = template_path
        @last = false
      end

      def parse
        eval(@template, binding, @template_path, 1)
        @root
      end


      # this is the little language for creating the system templates

      def system name, *args

        sys = Atlas::Template::SystemTemplate.new name

        if @last then
          @last.addChild(sys, 1)
        else
          @root = sys
        end
        @last = sys

        yield if block_given?
      end

      def server name, *args
        serv = Atlas::Template::ServerTemplate.new name, []
        @last.addChild(serv, 1);
      end

      def aka from, to

      end

      def override name, value

      end

    end
  end
end